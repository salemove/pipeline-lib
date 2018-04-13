package com.salemove

class Deployer implements Serializable {
  public static final triggerPattern = '!deploy'
  public static final deployStatusContext = 'continuous-integration/jenkins/pr-merge/deploy'
  public static final buildStatusContext = 'continuous-integration/jenkins/pr-merge'

  private static final containerName = 'deployer-container'
  private static final kubeConfFolderPath = '/root/.kube'
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
  private static final dockerRegistryURI = 'https://662491802882.dkr.ecr.us-east-1.amazonaws.com'
  private static final dockerRegistryCredentialsID = 'ecr:us-east-1:ecr-docker-push'

  private def script, kubernetesDeployment, image, inAcceptance, checklistFor
  Deployer(script, Map args) {
    this.script = script
    this.kubernetesDeployment = args.kubernetesDeployment
    this.image = args.image
    this.inAcceptance = args.inAcceptance
    this.checklistFor = args.checklistFor
  }

  static def containers(script) {
    [script.interactiveContainer(name: containerName, image: 'salemove/jenkins-toolbox:0f02bf5')]
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
      checkPRMergeable()
      prepareReleaseTool()
      def version = pushDockerImage()
      withLock('acceptance-environment') { deploy, rollBackForLockedResource ->
        deploy(envs.acceptance, version)
        runAcceptanceChecks()
        rollBackForLockedResource()
      }
      confirmNonAcceptanceDeploy()
      withLock('beta-and-prod-environments') { deploy, rollBackForLockedResource ->
        deploy(envs.beta, version)
        waitForValidationIn(envs.beta)
        script.parallel(
          US: { deploy(envs.prodUS, version) },
          EU: { deploy(envs.prodEU, version) }
        )
        waitForValidationIn(envs.prodUS)
        waitForValidationIn(envs.prodEU)
      }
      withLock('acceptance-environment') { deploy, rollBackForLockedResource ->
        deploy(envs.acceptance, version)
      }
      mergeToMaster()
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

  private def getCurrentVersion(String kubectlCmd) {
    shEval("${kubectlCmd} get deployment/${kubernetesDeployment} -o 'jsonpath={.metadata.labels.version}'")
  }

  private def notifyEnvDeploying(env, version, rollbackVersion) {
    script.slackSend(
      channel: env.slackChannel,
      message: "${deployingUser(script)} is updating deployment/${kubernetesDeployment} to" +
        " version ${version} in ${env.displayName}. The current version is ${rollbackVersion}." +
        " <${script.pullRequest.url}|PR ${script.pullRequest.number} - ${script.pullRequest.title}>"
    )
  }
  private def notifyEnvDeploySuccessful(env, version) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'good',
      message: "Successfully updated deployment/${kubernetesDeployment} to version ${version}" +
        " in ${env.displayName}."
    )
  }
  private def notifyEnvRollingBack(env, rollbackVersion) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Rolling back deployment/${kubernetesDeployment} to version ${rollbackVersion}" +
        " in ${env.displayName}."
    )
  }
  private def notifyEnvRollbackFailed(env, rollbackVersion) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'danger',
      message: "Failed to roll back deployment/${kubernetesDeployment} to version ${rollbackVersion}" +
        " in ${env.displayName}. Manual intervention is required!"
    )
  }

  private def notifyDeployFailedOrAborted() {
    script.pullRequest.createStatus(
      status: 'failure',
      context: deployStatusContext,
      description: 'Deploy either failed or was aborted',
      targetUrl: script.BUILD_URL
    )
    script.pullRequest.comment(
      "Deploy failed or was aborted. @${deployingUser(script)}, " +
      "please check [the logs](${script.BUILD_URL}/console) and try again."
    )
  }

  private def deployEnv(env, version) {
    def kubectlCmd = "kubectl" +
      " --kubeconfig=${kubeConfFolderPath}/config" +
      " --context=${env.kubeContext}" +
      " --namespace=default"
    def deployCmd = "${releaseProjectSubdir}/deploy_service.rb" +
      " --kubeconfig ${kubeConfFolderPath}/config" +
      " --environment ${env.kubeEnvName}" +
      " --context '${env.kubeContext}'" +
      ' --namespace default' +
      " --application ${kubernetesDeployment}" +
      ' --no-release-managed'

    def rollbackVersion
    def rollBack = {
      script.stage("Rolling back deployment in ${env.displayName}") {
        script.container(containerName) {
          notifyEnvRollingBack(env, rollbackVersion)
          try {
            script.timeout(deploymentUpdateTimeout) {
              script.sshagent([deployerSSHAgent]) {
                script.sh("${deployCmd} --version ${rollbackVersion}")
              }
            }
          } catch(e) {
            notifyEnvRollbackFailed(env, rollbackVersion)
            throw(e)
          }
        }
      }
    }

    script.stage("Deploying to ${env.displayName}") {
      script.container(containerName) {
        rollbackVersion = getCurrentVersion(kubectlCmd)
        notifyEnvDeploying(env, version, rollbackVersion)
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
        } catch(e) {
          // Handle rollout timeout here, instead of forcing the caller to handle
          // it, because the caller would only get the rollback closure after
          // this function returns, which it doesn't on timeout.
          rollBack()
          throw(e)
        }
        notifyEnvDeploySuccessful(env, version)
      }
    }

    return rollBack
  }

  private def runAcceptanceChecks() {
    script.stage('Running smoke tests') {
      inAcceptance()
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
      if (checklist.empty) {
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
      if (!uncheckedResponses.empty) {
        def formattedUncheckedResponses = uncheckedResponses.join(', ')
          .replaceFirst(/(.*), (.*?)$/, '$1, and $2') // Replace last comma with ", and"
        script.input("You left ${formattedUncheckedResponses} unchecked. Are you sure you want to continue?")
      }
    }
  }

  private def confirmNonAcceptanceDeploy() {
    script.stage('Waiting for permission before deploying to non-acceptance environments') {
      script.pullRequest.comment(
        "@${deployingUser(script)}, the changes were validated in acceptance. Please click **Proceed** " +
        "[here](${script.RUN_DISPLAY_URL}) (or [in the old UI](${script.BUILD_URL}/console)) to " +
        'continue the deployment.'
      )
      script.input('The change was validated in acceptance. Continue with other environments?')
    }
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
      def deploy = { Map env, String version ->
        rollbacks = [[
          lockedResource: resource,
          closure: deployEnv(env, version)
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
      notifyDeployFailedOrAborted()
      throw(e)
    }
  }

  private def executeRollbacks(rollbacks) {
    def exception
    rollbacks.each {
      try {
        it.closure()
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
        url: 'https://github.com/salemove/release.git',
        credentialsId: script.scm.userRemoteConfigs.first().credentialsId
      ]],
      extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: releaseProjectSubdir]]
    ])
    script.container(containerName) {
      script.sh("cd ${releaseProjectSubdir} && bundle install")
    }
  }

  private def mergeToMaster() {
    // Mark the current job's status as success, for the PR to be
    // mergeable.
    script.pullRequest.createStatus(
      status: 'success',
      context: buildStatusContext,
      description: 'The PR has successfully been deployed',
      targetUrl: script.BUILD_URL
    )
    // Mark a special deploy status as success, to indicate that the
    // job has also been successfully deployed.
    script.pullRequest.createStatus(
      status: 'success',
      context: deployStatusContext,
      description: 'The PR has successfully been deployed',
      targetUrl: script.BUILD_URL
    )

    script.sshagent([deployerSSHAgent]) {
      // Make sure the remote uses a SSH URL for the push to work. By
      // default it's an HTTPS URL, which when used to push a commit,
      // will require user input.
      def httpsOriginURL = shEval('git remote get-url origin')
      def sshOriginURL = httpsOriginURL.replaceFirst(/https:\/\/github.com\//, 'git@github.com:')
      script.sh("git remote set-url origin ${sshOriginURL}")

      // And then push the merge commit to master, closing the PR
      script.sh('git push origin @:master')
      // Clean up by deleting the now-merged branch
      script.sh("git push origin --delete ${script.pullRequest.headRef}")
    }
  }

  private def checkPRMergeable() {
    def nonSuccessStatuses = script.pullRequest.statuses
      // Ignore statuses that are managed by this build. They're expected to be
      // 'pending' at this point.
      .findAll { it.context != deployStatusContext && it.context != buildStatusContext }
      // groupBy + collect to reduce multiple pending statuses + success status
      // to a single success status. For non-success statuses, if there are
      // many different states, use the last one.
      .groupBy { it.context }
      .collect { context, statuses ->
        statuses.inject { finalStatus, status -> finalStatus.state == 'success' ? finalStatus : status }
      }
      .findAll { it.state != 'success' }

    if (!nonSuccessStatuses.empty) {
      def statusMessages = nonSuccessStatuses.collect { "Status ${it.context} is marked ${it.state}." }
      script.error("Commit is not ready to be merged. ${statusMessages.join(' ')}")
    }
  }

  private def pushDockerImage() {
    // Change commit author if merge commit is created by Jenkins
    def commitAuthor = shEval('git log -n 1 --pretty=format:\'%an\'')
    if (commitAuthor == 'Jenkins') {
      script.sh('git config user.name "sm-deployer"')
      script.sh('git config user.email "support@salemove.com"')
      script.sh('git commit --amend --no-edit --reset-author')
    }

    // Record version after possible author modification. This is the final
    // version that will be merged to master later.
    def version = shEval('git log -n 1 --pretty=format:\'%h\'')

    script.echo("Publishing docker image ${image.imageName()} with tag ${version}")
    script.docker.withRegistry(dockerRegistryURI, dockerRegistryCredentialsID) {
      image.push(version)
    }
    version
  }
}
