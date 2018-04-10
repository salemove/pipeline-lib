package com.salemove

class Deployer implements Serializable {
  public static final triggerPattern = '!deploy'
  public static final deployStatusContext = 'continuous-integration/jenkins/pr-merge/deploy'
  public static final buildStatusContext = 'continuous-integration/jenkins/pr-merge'

  private static final containerName = 'deployer-container'
  private static final kubeConfFolderPath = '/root/.kube'
  private static final envs = [
    acceptance: [
      displayName: 'acceptance',
      kubeEnvName: 'acceptance',
      kubeContext: '',
      slackChannel: '#ci'
    ],
    beta: [
      displayName: 'beta',
      kubeEnvName: 'staging',
      kubeContext: 'staging',
      slackChannel: '#beta'
    ],
    prodUS: [
      displayName: 'production US',
      kubeEnvName: 'production',
      kubeContext: 'prod-us',
      slackChannel: '#production'
    ],
    prodEU: [
      displayName: 'production EU',
      kubeEnvName: 'prod-eu',
      kubeContext: 'prod-eu',
      slackChannel: '#production'
    ]
  ]
  private static final deploymentUpdateTimeout = [time: 5, unit: 'MINUTES']
  private static final releaseProjectSubdir = '__release'
  private static final rootDirRelativeToReleaseProject = '..'
  private static final deployerSSHAgent = 'c5628152-9b4d-44ac-bd07-c3e2038b9d06'

  private def script, kubernetesDeployment, kubernetesContainer, imageTag, inAcceptance
  Deployer(script, Map args) {
    this.script = script
    this.kubernetesDeployment = args.kubernetesDeployment
    this.kubernetesContainer = args.kubernetesContainer
    this.imageTag = args.imageTag
    this.inAcceptance = args.inAcceptance
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
    prepareReleaseTool()
    withRollbackManagement { withLock ->
      withLock('acceptance-environment') { deploy, rollBackForLockedResource ->
        deploy(envs.acceptance)
        runAcceptanceChecks()
        rollBackForLockedResource()
      }
      confirmNonAcceptanceDeploy()
      withLock('beta-and-prod-environments') { deploy, rollBackForLockedResource ->
        deploy(envs.beta)
        waitForValidationIn(envs.beta)
        script.parallel(
          US: { deploy(envs.prodUS) },
          EU: { deploy(envs.prodEU) }
        )
        waitForValidationIn(envs.prodUS)
        waitForValidationIn(envs.prodEU)
      }
      withLock('acceptance-environment') { deploy, rollBackForLockedResource ->
        deploy(envs.acceptance)
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

  private def getCurrentImageTag(String kubectlCmd) {
    def currentImageJsonpath = "{.spec.template.spec.containers[?(@.name==\"${kubernetesContainer}\")].image}"
    shEval("${kubectlCmd} get deployment/${kubernetesDeployment} -o 'jsonpath=${currentImageJsonpath}'")
      .split(':')
      .last()
  }

  private def notifyDeploying(env, rollbackImageTag) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Updating deployment/${kubernetesDeployment} ${kubernetesContainer} image to tag ${imageTag}" +
        " in ${env.displayName}. The current ${kubernetesContainer} image tag is ${rollbackImageTag}."
    )
  }
  private def notifyDeploySuccessful(env) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'good',
      message: "Successfully updated deployment/${kubernetesDeployment} ${kubernetesContainer}" +
        " image to tag ${imageTag} in ${env.displayName}."
    )
  }
  private def notifyRollingBack(env, rollbackImageTag) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Rolling back deployment/${kubernetesDeployment} ${kubernetesContainer} image" +
        " to tag ${rollbackImageTag} in ${env.displayName}."
    )
  }
  private def notifyRollbackFailed(env, rollbackImageTag) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'danger',
      message: "Failed to roll back deployment/${kubernetesDeployment} ${kubernetesContainer}" +
        " image to tag ${rollbackImageTag} in ${env.displayName}. Manual intervention is required!"
    )
  }

  private def deployEnv(env) {
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

    def rollbackImageTag
    def rollBack = {
      script.stage("Rolling back deployment in ${env.displayName}") {
        script.container(containerName) {
          notifyRollingBack(env, rollbackImageTag)
          try {
            script.timeout(deploymentUpdateTimeout) {
              script.sshagent([deployerSSHAgent]) {
                script.sh("${deployCmd} --version ${rollbackImageTag}")
              }
            }
          } catch(e) {
            notifyRollbackFailed(env, rollbackImageTag)
            throw(e)
          }
        }
      }
    }

    script.stage("Deploying to ${env.displayName}") {
      script.container(containerName) {
        rollbackImageTag = getCurrentImageTag(kubectlCmd)
        notifyDeploying(env, rollbackImageTag)
        try {
          script.timeout(deploymentUpdateTimeout) {
            script.sshagent([deployerSSHAgent]) {
              // Specify --existing-repository-path, because this version of
              // code hasn't been pushed to GitHub yet and is only available locally
              script.sh(
                "${deployCmd} --existing-repository-path ${rootDirRelativeToReleaseProject}" +
                " --version ${imageTag}"
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
        notifyDeploySuccessful(env)
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
      script.input("Is the change OK in ${env.displayName}?")
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
      def deploy = { env ->
        rollbacks = [[
          lockedResource: resource,
          closure: deployEnv(env)
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
}
