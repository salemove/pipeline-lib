package com.salemove

import org.yaml.snakeyaml.Yaml
import com.salemove.Datadog
import com.salemove.deploy.Args
import com.salemove.deploy.Git
import com.salemove.deploy.Github
import com.salemove.deploy.Notify

class Deployer implements Serializable {
  public static final triggerPattern = /
    (?sx)          # Enable flags: DOTALL (dot matches newlines) and COMMENTS (enable these regex comments)
    \A
    \s*            # Allow optional whitespace before the command
    !deploy
    (?:            # Don`t capture the whitespace before arguments
      \s+          # Force whitespace between command and arguments
      (?<args>.*?) # Capture arguments with group named "args", non-greedy to avoid capturing trailing whitespace
    )?             # Arguments are optional
    \s*            # Allow optional whitespace after the command
    \z
  /.trim()
  private static final compiledTriggerPattern = ~triggerPattern

  private static final containerName = 'deployer-container'
  private static final kubeConfFolderPath = '/root/.kube_conf'
  private static final envs = [
    acceptance: [
      name: 'acceptance',
      displayName: 'acceptance',
      kubeEnvName: 'acceptance',
      domainName: 'at.samo.io',
      slackChannel: '#ci'
    ],
    beta: [
      name: 'beta',
      displayName: 'beta',
      kubeEnvName: 'beta',
      domainName: 'beta.salemove.com',
      slackChannel: '#beta'
    ],
    prodUS: [
      name: 'prod-us',
      displayName: 'production US',
      kubeEnvName: 'prod-us',
      domainName: 'salemove.com',
      slackChannel: '#production'
    ],
    prodEU: [
      name: 'prod-eu',
      displayName: 'production EU',
      kubeEnvName: 'prod-eu',
      domainName: 'salemove.eu',
      slackChannel: '#production'
    ]
  ]
  private static final releaseProjectSubdir = '__release'
  private static final rootDirRelativeToReleaseProject = '..'
  private static final deployerSSHAgent = 'c5628152-9b4d-44ac-bd07-c3e2038b9d06'
  private static final dockerRegistryURI = '662491802882.dkr.ecr.us-east-1.amazonaws.com'
  private static final dockerRegistryCredentialsID = 'ecr:us-east-1:ecr-docker-push'
  private static final defaultNamespace = 'default'

  private def script, kubernetesDeployment, image, inAcceptance, preDeploymentChecksFor, automaticChecksFor,
    checklistFor, kubernetesNamespace, notify, git, github, globalLockConfigured,
    deploymentUpdateTimeout, datadog
  Deployer(script, Map args) {
    def defaultArgs = [
      kubernetesNamespace: 'default',
      lockGlobally: true,
      deploymentUpdateTimeout: [time: 10, unit: 'MINUTES']
    ]
    def finalArgs = defaultArgs << args

    this.script = script
    this.kubernetesDeployment = finalArgs.kubernetesDeployment
    this.image = finalArgs.image
    this.inAcceptance = finalArgs.inAcceptance

    this.preDeploymentChecksFor = finalArgs.preDeploymentChecksFor
    this.automaticChecksFor = finalArgs.automaticChecksFor
    this.checklistFor = finalArgs.checklistFor
    this.kubernetesNamespace = finalArgs.kubernetesNamespace
    this.globalLockConfigured = finalArgs.lockGlobally
    this.deploymentUpdateTimeout = finalArgs.deploymentUpdateTimeout
    this.notify = new Notify(script, finalArgs)
    this.git = new Git(script)
    this.github = new Github(script, finalArgs)
    this.datadog = new Datadog(script)
  }

  static def isDeploy(script) {
    def triggerCause = getTriggerCause(script)
    triggerCause && triggerCause.triggerPattern == triggerPattern
  }
  static def validateTriggerArgs(script) {
    def args = getTriggerArgs(script)
    if (args && args != Args.noGlobalLock) {
      // Instantiate the Notify class with empty arguments, because this is a
      // static method and we don't have these arguments yet and they're also
      // not required for that particular notification.
      def notify = new Notify(script, [:])
      notify.unexpectedArgs()
      script.error("Unexpected !deploy argument(s): \"${args}\". Expecting either nothing or '${Args.noGlobalLock}'.")
    }
  }

  static def deployingUser(script) {
    getTriggerCause(script).userLogin
  }

  private static def getTriggerCause(script) {
    script.currentBuild.rawBuild.getCause(
      org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause
    )
  }

  def deploy() {
    github.checkPRMergeable(notifyOnInput: true)
    git.withRemoteTag { version ->
      pushDockerImage(version)
      inDeployerPod(version) {
        prepareReleaseTool()
        withRollbackManagement { withLock ->
          withLock(testEnvLock()) { deploy, rollBackForLockedResource ->
            deploy(env: envs.acceptance, version: version)
            rollBackForLockedResource()
          }
          confirmNonAcceptanceDeploy()
          withLock(nonTestEnvLock()) { deploy, rollBackForLockedResource ->
            git.checkMasterHasNotChanged()
            github.checkPRMergeable(notifyOnInput: false)
            deploy(env: envs.beta, version: version)
            notify.inputRequiredInBeta()
            waitForValidationIn(envs.beta)
            notify.prodDeploying(version)

            if (!preDeploymentChecksFor) {
              script.parallel(
                US: { deploy(env: envs.prodUS, version: version) },
                EU: { deploy(env: envs.prodEU, version: version) }
              )
              waitForValidationIn(envs.prodUS)
              waitForValidationIn(envs.prodEU)
            } else {
              deploy(env: envs.prodUS, version: version)
              waitForValidationIn(envs.prodUS)

              deploy(env: envs.prodEU, version: version)
              waitForValidationIn(envs.prodEU)
            }

            withLock(testEnvLock(onlyLocal: true)) { deployWithATLock, _ ->
              deployWithATLock(env: envs.acceptance, version: version, runAutomaticChecks: false)
            }
            mergeToMaster()
          }
        }
      }
    }
  }

  def pushImageForNextDeploy() {
    def version = git.getPersistentVersion()
    pushDockerImage(version)
  }

  static def buildImageIfDoesNotExist(script, String imageName, Closure body) {
    def version = new Git(script).getPersistentVersion()
    def taggedImageName = "${imageName}:${version}"
    def image = script.docker.image(taggedImageName)
    script.echo("Checking if image ${taggedImageName} already exists")
    try {
      script.docker.withRegistry("https://${dockerRegistryURI}", dockerRegistryCredentialsID) {
        image.pull()
        // Ensure that the image is available with the correct name + tag by
        // tagging the image locally without the registry prefix as well.
        // Otherwise the image can't be referred to without the registry
        // prefix. Has to be done within `withRegistry` for `image.imageName()`
        // to include the registry prefix.
        script.sh("docker tag ${image.imageName()} ${taggedImageName}")
      }
      script.echo("Image ${taggedImageName} already exists. Using it instead of rebuilding")
    } catch(e) {
      script.echo("Image ${taggedImageName} does not exist. Building a new image")
      image = body()
      // Ensure that the image is available with the correct name + tag. If a
      // different name was specified for `docker.build` in `body` then this
      // ensures that `imageName` is used. This also alleviates race conditions
      // caused by using a `:latest` tag by default.
      //
      // `sh` with `docker tag` is used instead of `image.tag`, because
      // `image.tag` uses `image.id` instead of `image.imageName()`, which
      // doesn't always use a fully qualified name.
      script.sh("docker tag ${image.imageName()} ${taggedImageName}")
    }
    return script.docker.image(taggedImageName)
  }

  private def shEval(String cmd) {
    def secureCmd = """\
    #!/bin/bash
    set -e
    set -o pipefail

    ${cmd}
    """
    script.sh(returnStdout: true, script: secureCmd).trim()
  }

  // `kubectl patch` isn't idempotent and fails when trying to delete a field
  // that doesn't exist. This helper function allows running `kubectl patch`
  // idempotently by inspecting it's output and only failing when the patch
  // fails for a different reason.
  private def kubePatch(String kubectlCmd, String resource, String patchCmd) {
    def cmd = """\
    #!/bin/bash
    set +e

    result=\$(${kubectlCmd} patch ${resource} --type=json -p='[${patchCmd}]')
    code="\$?"
    if [[ "\$code" != "0" && "\$result" == *" not patched" ]]; then
      echo "\$result" 1>&2
      exit "\$code"
    fi
    """
    script.sh(cmd)
  }

  // Try getting the version first from the pod template labels and fall back
  // to the deployment labels. This makes sure the correct version is used when
  // the deployment is manually rolled back with `kubectl rollout undo`.
  private def getCurrentVersion(String kubectlCmd) {
    def templateVersion = shEval(
      "${kubectlCmd} get deployment/${kubernetesDeployment} " +
      "-o 'jsonpath={.spec.template.metadata.labels.version}'"
    )

    templateVersion ?: shEval(
      "${kubectlCmd} get deployment/${kubernetesDeployment} " +
      "-o 'jsonpath={.metadata.labels.version}'"
    )
  }

  private def hasExistingDeployment(String kubectlCmd) {
    try {
      script.sh("${kubectlCmd} get deployment/${kubernetesDeployment}")
      true
    } catch(e) {
      false
    }
  }

  private def deployEnv(Map args) {
    def defaultArgs = [
      runAutomaticChecks: true
    ]
    def finalArgs = defaultArgs << args

    def env = finalArgs.env
    def version = finalArgs.version
    def repository = git.getRepositoryName()

    def kubectlCmd = "kubectl" +
      " --kubeconfig=${kubeConfFolderPath}/config" +
      " --context=${env.kubeEnvName}" +
      " --namespace=${kubernetesNamespace}"
    def deployCmd = "${releaseProjectSubdir}/deploy_service.rb" +
      " --kubeconfig ${kubeConfFolderPath}/config" +
      " --environment ${env.kubeEnvName}" +
      " --context '${env.kubeEnvName}'" +
      " --namespace ${kubernetesNamespace}" +
      " --application ${kubernetesDeployment}" +
      " --repository ${repository}" +
      ' --no-release-managed' +
      ' --pod-node-selector role=application'

    def rollBack
    def rollbackForVersion = { rollbackVersion ->
      return {
        script.stage("Rolling back deployment in ${env.displayName}") {
          script.container(containerName) {
            notify.envRollingBack(env, rollbackVersion)
            try {
              script.timeout(deploymentUpdateTimeout) {
                script.sshagent([deployerSSHAgent]) {
                  script.sh("${deployCmd} --version ${rollbackVersion}")
                }
              }
            } catch(e) {
              notify.envRollbackFailed(env, rollbackVersion)
              throw(e)
            }
          }
        }
      }
    }
    def rollbackForInitialDeploy = {
      return {
        script.stage("Deleting deployment in ${env.displayName}") {
          script.container(containerName) {
            notify.envDeletingDeploy(env)
            try {
              script.timeout(deploymentUpdateTimeout) {
                script.sh("${kubectlCmd} delete deployment/${kubernetesDeployment}")
              }
            } catch(e) {
              notify.envDeployDeletionFailed(env)
              throw(e)
            }
          }
        }
      }
    }
    def rollbackWithUndo = {
      return {
        script.stage("Undoing deployment in ${env.displayName}") {
          script.container(containerName) {
            notify.envUndoingDeploy(env)
            try {
              script.timeout(deploymentUpdateTimeout) {
                script.sh("${kubectlCmd} rollout undo deployment/${kubernetesDeployment}")
                script.sh("${kubectlCmd} rollout status deployment/${kubernetesDeployment}")
                script.sh("${kubectlCmd} label deployment/${kubernetesDeployment} version-")
                kubePatch(
                  kubectlCmd,
                  "deployment/${kubernetesDeployment}",
                  '{"op": "remove", "path": "/spec/template/metadata/labels/version"}'
                )
              }
            } catch(e) {
              notify.envUndoFailed(env)
              throw(e)
            }
          }
        }
      }
    }

    script.stage("Deploying to ${env.displayName}") {
      script.container(containerName) {
        if (finalArgs.runAutomaticChecks) {
          runPreDeploymentChecks(kubectlCmd, env, version)
        }

        if (hasExistingDeployment(kubectlCmd)) {
          def rollbackVersion = getCurrentVersion(kubectlCmd)
          if (rollbackVersion) {
            rollBack = rollbackForVersion(rollbackVersion)
            notify.envDeploying(env, version, rollbackVersion, repository)
          } else {
            if (env.name == 'acceptance') {
              // User might not be watching the job logs at this stage. Notify them via GitHub.
              notify.inputRequired()
            }
            // Ask user to confirm that the missing version is expected
            confirmFirstVersionedDeploy(env)
            rollBack = rollbackWithUndo()
            notify.envDeployingVersionedForFirstTime(env, version)
          }
        } else {
          if (env.name == 'acceptance') {
            // User might not be watching the job logs at this stage. Notify them via GitHub.
            notify.inputRequired()
          }
          // Ask user to confirm that the missing deployment is expected
          confirmInitialDeploy(env)
          rollBack = rollbackForInitialDeploy()
          notify.envDeployingForFirstTime(env, version)
        }
        try {
          script.timeout(deploymentUpdateTimeout) {
            script.sshagent([deployerSSHAgent]) {
              // Specify --existing-repository-path, because this version of
              // code hasn't been pushed to GitHub yet and is only available locally
              script.sh(
                "${deployCmd} --existing-repository-path ${rootDirRelativeToReleaseProject}" +
                " --version ${version}"
              )
            }
          }
          notify.envDeploySuccessful(env, version)

          if (finalArgs.runAutomaticChecks) {
            runAutomaticChecks(kubectlCmd, env, version)
          }
        } catch(e) {
          // Handle rollout timeout here, instead of forcing the caller to handle
          // it, because the caller would only get the rollback closure after
          // this function returns, which it doesn't on timeout.
          rollBack()
          throw(e)
        }
      }
    }

    return rollBack
  }

  private def runPreDeploymentChecks(kubectlCmd, env, version) {
    if (!preDeploymentChecksFor) {
      script.echo('No pre-deployment checks are defined for this job, skipping.')
      return
    }

    script.stage("Running pre-deployment checks in ${env.displayName}") {
      preDeploymentChecksFor.call(env.subMap(['name', 'domainName']) << [
        runInKube: { Map args -> runInKube(kubectlCmd, version, args) },
        version: version,
        kubectlCmd: kubectlCmd
      ])
    }
  }

  private def runAutomaticChecks(kubectlCmd, env, version) {
    // Also run the deprecated `inAcceptance` closure if it's included and we
    // just deployed to acceptance env. This functionality is kept for
    // backwards compatibility only.
    if (env.name == 'acceptance' && inAcceptance) {
      script.echo('`inAcceptance` is deprecated. Please use `automaticChecksFor` instead.')

      script.stage('Running acceptance tests') {
        inAcceptance.call()
      }
    }

    if (!automaticChecksFor) {
      script.echo('No automatic checks defined for this job. Not running automatic checks.')
      return
    }

    script.stage("Running automatic checks in ${env.displayName}") {
      automaticChecksFor.call(env.subMap(['name', 'domainName']) << [
        runInKube: { Map args -> runInKube(kubectlCmd, version, args) },
        version: version,
        kubectlCmd: kubectlCmd
      ])
    }
  }

  private def runInKube(kubectlCmd, version, Map args) {
    def uniqueShortID = UUID.randomUUID().toString().replaceFirst(/^.*-/, '')

    def defaultArgs = [
      image: "${dockerRegistryURI}/${image.id.replaceFirst(/:.*$/, '')}:${version}",
      name: "${kubernetesDeployment}-checks-${uniqueShortID}",
      overwriteEntrypoint: false,
      additionalArgs: '',
      returnStdout: false,
      returnStatus: false
    ]

    def finalArgs = defaultArgs << args

    script.ansiColor('xterm') {
      script.sh(
        script: "${kubectlCmd} run" +
          " ${finalArgs.name}" +
          " --image='${finalArgs.image}'" +
          ' --restart=Never' +
          ' --attach' +
          ' --rm' +
          " ${finalArgs.additionalArgs}" +
          " ${finalArgs.overwriteEntrypoint ? '--command' : ''}" +
          " -- ${finalArgs.command}",
        returnStatus: finalArgs.returnStatus,
        returnStdout: finalArgs.returnStdout
      )
    }
  }

  private def waitForValidationIn(env) {
    script.stage("Validation in ${env.displayName}") {
      def question = "Is the change OK in ${env.displayName}?"
      if (!checklistFor) {
        script.input(question)
        return
      }

      def checklist = checklistFor.call(env.subMap(['name', 'domainName']))
      if (checklist.size() == 0) {
        script.input(question)
        return
      }

      def response = script.input(
        message: "${question} Please fill the following checklist before continuing.",
        parameters: checklist.collect { script.booleanParam(it + [defaultValue: false]) }
      )

      // input returns just the value if it has only one parameter, and a map of
      // values otherwise. Create a list of names that have `false` values from
      // that response.
      def uncheckedResponses
      if (checklist.size() == 1) {
        uncheckedResponses = response ? [] : [checklist.first().name]
      } else {
        uncheckedResponses = response
          .findAll { name, isChecked -> !isChecked }
          .collect { name, isChecked -> name }
      }
      if (uncheckedResponses.size() > 0) {
        def formattedUncheckedResponses = uncheckedResponses.join(', ')
          .replaceFirst(/(.*), (.*?)$/, '$1, and $2') // Replace last comma with ", and"
        script.input("You left ${formattedUncheckedResponses} unchecked. Are you sure you want to continue?")
      }
    }
  }

  private def confirmNonAcceptanceDeploy() {
    script.stage('Waiting for permission before deploying to non-acceptance environments') {
      notify.inputRequiredPostAcceptanceValidation()
      script.input('The change was validated in acceptance. Continue with other environments?')
    }
  }

  private def confirmInitialDeploy(env) {
    script.input(
      "Failed to find an existing deployment in ${env.displayName}. This is expected if deploying an " +
      'application for the first time, but indicates an issue otherwise. Proceeding means that in ' +
      'case of failure, the deploy is rolled back by deleting the Kubernetes Deployment. Services ' +
      'and other resources are left as-is and are expected to be overwritten by future deploys or ' +
      'removed manually. Do you want to continue?'
    )
  }

  private def confirmFirstVersionedDeploy(env) {
    script.input(
      "Failed to find a 'version' label in ${env.displayName}. This is expected if deploying an " +
      'application whose deploys have previously been managed manually (e.g. from sm-configuration), ' +
      'but indicates an issue otherwise. Proceeding means that in case of failure, the deploy is ' +
      'rolled back with `kubectl rollout undo`. Services and other resources are left as-is and are ' +
      'expected to be overwritten by future deploys or removed manually. Do you want to continue?'
    )
  }

  private def inDeployerPod(String version, Closure body) {
    script.withCredentials([script.string(credentialsId: 'eks-deployer-iam-role', variable: 'role')]) {
      script.inDockerAgent(
        name: 'deployer',
        containers: [script.toolboxContainer(name: containerName)],
        volumes: [script.configMapVolume(mountPath: kubeConfFolderPath, configMapName: 'kube-config')],
        annotations: [script.podAnnotation(key: 'iam.amazonaws.com/role', value: script.role)],
        yaml: addNodeSelector(Datadog.podYAML, [role: 'application'])
      ) {
        git.checkoutVersionTag(version)
        body()
      }
    }
  }

  @NonCPS
  private def addNodeSelector(String podYAML, Map nodeSelector) {
    def serializer = new Yaml()
    def resultMap = (Map) serializer.load(podYAML)

    resultMap.spec.nodeSelector = nodeSelector

    serializer.dump(resultMap)
  }

  private def withRollbackManagement(Closure body) {
    def rollbacks = []
    def rollBackAll = {
      def rollBackByResource = rollbacks
        .groupBy { it.lockedResource }
        .collectEntries { lockedResource, rollbacksForResource ->
          [(lockedResource): {
            def timeStart = new Date()
            script.lock(lockedResource) {
              def timeEnd = new Date()
              recordLockWaitDuration(lockedResource, timeStart, timeEnd)

              executeRollbacks(rollbacksForResource)
            }
          }]
        }
      try {
        script.parallel(rollBackByResource)
      } finally {
        rollbacks = []
      }
    }

    def withLock = { resource, Closure withLockBody ->
      def deploy = { Map args ->
        rollbacks = [[
          lockedResource: resource,
          closure: deployEnv(args)
        ]] + rollbacks
      }
      def rollBackForLockedResource = {
        def (toRollBack, toRetain) = rollbacks.split { it.lockedResource == resource }
        try {
          executeRollbacks(toRollBack)
        } finally {
          rollbacks = toRetain
        }
      }

      def timeStart = new Date()
      script.lock(resource) {
        def timeEnd = new Date()
        recordLockWaitDuration(resource, timeStart, timeEnd)

        try {
          withLockBody(deploy, rollBackForLockedResource)
        } catch(e) {
          rollBackForLockedResource()
          throw(e)
        }
      }
    }

    try {
      body(withLock)
    } catch(e) {
      script.echo('Deploy either failed or was aborted. Rolling back changes in all affected environments.')
      rollBackAll()
      github.setStatus(status: 'failure', description: 'Deploy either failed or was aborted')
      notify.deployFailedOrAborted()
      throw(e)
    }
  }

  private def recordLockWaitDuration(resource, Date start, Date end) {
    def resourceNames = [resource.resource] +
      (resource['extra'] ?: []).collect { it.resource }

    resourceNames.each { name ->
      datadog.sendDuration(
        nameSuffix: 'deploy.lock.wait.duration',
        start: start,
        end: end,
        tags: ["resource:${name}"]
      )
    }
  }

  private def executeRollbacks(rollbacks) {
    def exception
    rollbacks.each {
      try {
        it['closure']()
      } catch(e) {
        exception = e
        script.echo("The following exception was thrown. Continuing regardless. ${e}!")
      }
    }
    if (exception) {
      // Re-throw the last exception, if there were any, once all rollbacks
      // have been executed.
      throw(exception)
    }
  }

  private def prepareReleaseTool() {
    script.checkout([
      $class: 'GitSCM',
      branches: [[name: 'rm-git-pull']],
      userRemoteConfigs: [[
        url: 'git@github.com:salemove/release.git',
        credentialsId: deployerSSHAgent
      ]],
      extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: releaseProjectSubdir]]
    ])
    script.container(containerName) {
      script.sh("cd ${releaseProjectSubdir} && bundle install")
    }
  }

  private def mergeToMaster() {
    def attempt = 0
    def abortException

    script.retry(3) {
      attempt++

      if (abortException) {
        // Avoid retrying if the user has intentionally aborted the execution.
        throw(abortException)
      } else if (attempt > 1) {
        notify.inputRequired()
        try {
          script.input(
            'The merge failed. There is information about the reason above. It could have failed ' +
            'due to serious problems, like missing some changes that have been merged to master ' +
            'during the deploy. If that is the case, then please click Abort and your changes will ' +
            'be rolled back in all environments. However, the merge could also have failed because ' +
            'of a fixable issue, like not having a required review or missing some status checks. ' +
            'If that is the case, then please fix the problem and click Proceed to try again.'
          )
        } catch(e) {
          // Set abortException so we know that the user intentionally aborted
          // the execution and we don't keep retrying when that's the case.
          abortException = e
          throw(e)
        }
      }

      try {
        // Mark the current job's status as success, for the PR to be mergeable.
        github.setStatus(status: 'success', description: 'The PR has successfully been deployed')

        git.finishMerge()
      } catch(e) {
        script.echo("Merge failed with the following exception. Waiting a bit and trying again. ${e}!")
        script.sleep(time: 5, unit: 'SECONDS')
        throw(e)
      }
    }
  }

  private def pushDockerImage(String version) {
    script.echo("Publishing docker image ${image.imageName()} with tag ${version}")
    script.docker.withRegistry("https://${dockerRegistryURI}", dockerRegistryCredentialsID) {
      image.push(version)
    }
  }

  private def testEnvLock(Map args = [:]) {
    envLock('acceptance-environment', args)
  }
  private def nonTestEnvLock(Map args = [:]) {
    envLock('beta-and-prod-environments', args)
  }
  private def envLock(String envName, Map args) {
    def localLock = "${kubernetesDeployment}-${kubernetesNamespace}-${envName}"
    if (globalLockRequired(args)) {
      [resource: localLock, extra: [[resource: envName]]]
    } else {
      [resource: localLock]
    }
  }
  private def globalLockRequired(args) {
    // This assumes that the arguments have already been validated

    globalLockConfigured && // Configuration from Jenkinsfile
      getTriggerArgs(script) != Args.noGlobalLock && // !deploy PR comment parameter
      !args.onlyLocal // Code override
  }
  private static def getTriggerArgs(script) {
    def triggerCause = getTriggerCause(script)
    def matcher = compiledTriggerPattern.matcher(triggerCause.comment)
    if (!matcher.matches()) {
      // Assuming this is only called when the build is triggered because of triggerPattern
      script.echo(
        'Something\'s wrong! Deploy trigger pattern does not match triggering comment.' +
        ' This is unexpected and means that we can not parse arguments.' +
        ' Continuing under the assumption that no arguments were specified.'
      )
      return null
    }

    matcher.group('args')
  }
}
