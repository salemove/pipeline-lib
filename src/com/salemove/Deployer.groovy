package com.salemove

import com.salemove.deploy.Git
import com.salemove.deploy.Github
import com.salemove.deploy.Notify

class Deployer implements Serializable {
  public static final triggerPattern = '!deploy'

  private static final containerName = 'deployer-container'
  private static final kubeConfFolderPath = '/root/.kube_conf'
  private static final envs = [
    acceptance: [
      name: 'acceptance',
      displayName: 'acceptance',
      kubeEnvName: 'acceptance',
      kubeContext: '',
      domainName: 'at.samo.io',
      slackChannel: '#ci'
    ],
    beta: [
      name: 'beta',
      displayName: 'beta',
      kubeEnvName: 'staging',
      kubeContext: 'staging',
      domainName: 'beta.salemove.com',
      slackChannel: '#beta'
    ],
    prodUS: [
      name: 'prod-us',
      displayName: 'production US',
      kubeEnvName: 'production',
      kubeContext: 'prod-us',
      domainName: 'salemove.com',
      slackChannel: '#production'
    ],
    prodEU: [
      name: 'prod-eu',
      displayName: 'production EU',
      kubeEnvName: 'prod-eu',
      kubeContext: 'prod-eu',
      domainName: 'salemove.eu',
      slackChannel: '#production'
    ]
  ]
  private static final deploymentUpdateTimeout = [time: 10, unit: 'MINUTES']
  private static final releaseProjectSubdir = '__release'
  private static final rootDirRelativeToReleaseProject = '..'
  private static final deployerSSHAgent = 'c5628152-9b4d-44ac-bd07-c3e2038b9d06'
  private static final dockerRegistryURI = '662491802882.dkr.ecr.us-east-1.amazonaws.com'
  private static final dockerRegistryCredentialsID = 'ecr:us-east-1:ecr-docker-push'
  private static final defaultNamespace = 'default'

  private def script, kubernetesDeployment, image, inAcceptance, automaticChecksFor,
    checklistFor, kubernetesNamespace, notify, git, github
  Deployer(script, Map args) {
    def defaultArgs = [
      kubernetesNamespace: 'default'
    ]
    def finalArgs = defaultArgs << args

    this.script = script
    this.kubernetesDeployment = finalArgs.kubernetesDeployment
    this.image = finalArgs.image
    this.inAcceptance = finalArgs.inAcceptance
    this.automaticChecksFor = finalArgs.automaticChecksFor
    this.checklistFor = finalArgs.checklistFor
    this.kubernetesNamespace = finalArgs.kubernetesNamespace
    this.notify = new Notify(script, finalArgs)
    this.git = new Git(script)
    this.github = new Github(script, finalArgs)
  }

  static def containers(script) {
    [script.interactiveContainer(name: containerName, image: 'salemove/jenkins-toolbox:e38f8db')]
  }
  static def volumes(script) {
    [script.secretVolume(mountPath: kubeConfFolderPath, secretName: 'kube-config')]
  }

  static def isDeploy(script) {
    def triggerCause = getTriggerCause(script)
    triggerCause && triggerCause.triggerPattern == triggerPattern
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
    withRollbackManagement { withLock ->
      github.checkPRMergeable(notifyOnInput: true)
      prepareReleaseTool()
      def version = pushDockerImage()
      withLock('acceptance-environment') { deploy, rollBackForLockedResource ->
        deploy(env: envs.acceptance, version: version)
        rollBackForLockedResource()
      }
      confirmNonAcceptanceDeploy()
      withLock('beta-and-prod-environments') { deploy, rollBackForLockedResource ->
        git.checkMasterHasNotChanged()
        github.checkPRMergeable(notifyOnInput: false)
        deploy(env: envs.beta, version: version)
        waitForValidationIn(envs.beta)
        script.parallel(
          US: { deploy(env: envs.prodUS, version: version) },
          EU: { deploy(env: envs.prodEU, version: version) }
        )
        waitForValidationIn(envs.prodUS)
        waitForValidationIn(envs.prodEU)
        withLock('acceptance-environment') { deployWithATLock, _ ->
          deployWithATLock(env: envs.acceptance, version: version, runAutomaticChecks: false)
        }
        mergeToMaster()
      }
    }
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

    def kubectlCmd = "kubectl" +
      " --kubeconfig=${kubeConfFolderPath}/config" +
      " --context=${env.kubeContext}" +
      " --namespace=${kubernetesNamespace}"
    def deployCmd = "${releaseProjectSubdir}/deploy_service.rb" +
      " --kubeconfig ${kubeConfFolderPath}/config" +
      " --environment ${env.kubeEnvName}" +
      " --context '${env.kubeContext}'" +
      " --namespace ${kubernetesNamespace}" +
      " --application ${kubernetesDeployment}" +
      " --repository ${git.getRepositoryName()}" +
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
        if (hasExistingDeployment(kubectlCmd)) {
          def rollbackVersion = getCurrentVersion(kubectlCmd)
          if (rollbackVersion) {
            rollBack = rollbackForVersion(rollbackVersion)
            notify.envDeploying(env, version, rollbackVersion)
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

  private def runAutomaticChecks(kubectlCmd, env, version) {
    // Also run the deprecated `inAcceptance` closure if it's included and we
    // just deployed to acceptance env. This functionality is kept for
    // backwards compatibility only.
    if (env.name == 'acceptance' && inAcceptance) {
      script.echo('`inAcceptance` is deprecated. Please use `automaticChecksFor` instead.')

      script.stage('Running acceptance tests') {
        inAcceptance()
      }
    }

    if (!automaticChecksFor) {
      script.echo('No automatic checks defined for this job. Not running automatic checks.')
      return
    }

    script.stage("Running automatic checks in ${env.displayName}") {
      automaticChecksFor(env.subMap(['name', 'domainName']) << [
        runInKube: { Map args ->
          def defaultArgs = [image: "${dockerRegistryURI}/${image.id.replaceFirst(/:.*$/, '')}:${version}"]
          def finalArgs = defaultArgs << args

          def uniqueShortID = UUID.randomUUID().toString().replaceFirst(/^.*-/, '')
          script.ansiColor('xterm') {
            script.sh(
              "${kubectlCmd} run" +
              " ${kubernetesDeployment}-checks-${uniqueShortID}" +
              " --image='${finalArgs.image}'" +
              ' --restart=Never' +
              ' --tty --stdin' +
              ' --rm' +
              " ${finalArgs.additionalArgs}" +
              " -- ${finalArgs.command}"
            )
          }
        }
      ])
    }
  }

  private def waitForValidationIn(env) {
    script.stage("Validation in ${env.displayName}") {
      def question = "Is the change OK in ${env.displayName}?"
      if (!checklistFor) {
        script.input(question)
        return
      }

      def checklist = checklistFor(env.subMap(['name', 'domainName']))
      if (checklist.size() == 0) {
        script.input(question)
        return
      }

      def response = script.input(
        message: "${question} Please fill the following checklist before continuing.",
        parameters: checklist.collect { script.booleanParam(it + [defaultValue: false]) }
      )

      // input returns just the value if it has only one paramter, and a map of
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

  private def withRollbackManagement(Closure body) {
    def rollbacks = []
    def rollBackAll = {
      def rollBackByResource = rollbacks
        .groupBy { it.lockedResource }
        .collectEntries { lockedResource, rollbacksForResource ->
          [(lockedResource): {
            script.lock(lockedResource) {
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

    def withLock = { String resource, Closure withLockBody ->
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

      script.lock(resource) {
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
      branches: [[name: 'master']],
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
    // Mark the current job's status as success, for the PR to be mergeable.
    github.setStatus(status: 'success', description: 'The PR has successfully been deployed')

    git.finishMerge()
  }

  private def pushDockerImage() {
    git.resetMergeCommitAuthor()
    // Record version after possible author modification. This is the final
    // version that will be merged to master later.
    def version = git.getShortRevision()

    script.echo("Publishing docker image ${image.imageName()} with tag ${version}")
    script.docker.withRegistry("https://${dockerRegistryURI}", dockerRegistryCredentialsID) {
      image.push(version)
    }
    version
  }
}
