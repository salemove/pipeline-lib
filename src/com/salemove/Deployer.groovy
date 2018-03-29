package com.salemove

class Deployer implements Serializable {
  private static final kubectlContainer = 'kubectl'
  private static final kubeConfFolderPath = '/root/.kube'
  private static final envs = [
    acceptance: [
      name: 'acceptance',
      kubeContext: '',
      slackChannel: '#ci'
    ],
    beta: [
      name: 'beta',
      kubeContext: 'staging',
      slackChannel: '#beta'
    ],
    prodUS: [
      name: 'production US',
      kubeContext: 'prod-us',
      slackChannel: '#production'
    ],
    prodEU: [
      name: 'production EU',
      kubeContext: 'prod-eu',
      slackChannel: '#production'
    ]
  ]
  private static final deploymentUpdateTimeout = [time: 5, unit: 'MINUTES']

  private def script, kubernetesDeployment, kubernetesContainer, imageName
  Deployer(script, Map args) {
    this.script = script
    this.kubernetesDeployment = args.kubernetesDeployment
    this.kubernetesContainer = args.kubernetesContainer
    this.imageName = args.imageName
  }

  def deploy() {
    script.inPod(
      containers: [script.interactiveContainer(name: kubectlContainer, image: 'lachlanevenson/k8s-kubectl:latest')],
      volumes: [script.secretVolume(mountPath: kubeConfFolderPath, secretName: 'kube-config')]
    ) {
      withRollbackManagement { deploy, rollBack ->
        script.lock('acceptance-environment') {
          deploy(envs.acceptance)
          runSmokes()
          rollBack()
        }
        script.lock('beta-and-prod-environments') {
          deploy(envs.beta)
          waitForValidationIn(envs.beta)
          script.parallel(
            US: { deploy(envs.prodUS) },
            EU: { deploy(envs.prodEU) }
          )
          waitForValidationIn(envs.prodUS)
          waitForValidationIn(envs.prodEU)
        }
        script.lock('acceptance-environment') {
          deploy(envs.acceptance)
        }
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

  private def getCurrentImage(String kubectlCmd) {
    def currentImageJsonpath = "{.spec.template.spec.containers[?(@.name==\"${kubernetesContainer}\")].image}"
    shEval("${kubectlCmd} get deployment/${kubernetesDeployment} -o 'jsonpath=${currentImageJsonpath}'")
  }

  private def notifyDeploying(env, rollbackImage) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Updating deployment/${kubernetesDeployment} image to ${kubernetesContainer}=${imageName}" +
        " in ${env.name}. The current image is ${kubernetesContainer}=${rollbackImage}."
    )
  }
  private def notifyDeploySuccessful(env) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'good',
      message: "Successfully updated deployment/${kubernetesDeployment} image to" +
        " ${kubernetesContainer}=${imageName} in ${env.name}."
    )
  }
  private def notifyRollingBack(env, rollbackImage) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Rolling back deployment/${kubernetesDeployment} image to ${kubernetesContainer}=${rollbackImage}" +
        " in ${env.name}."
    )
  }
  private def notifyRollbackFailed(env, rollbackImage) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'danger',
      message: "Failed to roll back deployment/${kubernetesDeployment} image to" +
        " ${kubernetesContainer}=${rollbackImage} in ${env.name}. Manual intervention is required!"
    )
  }

  private def deployEnv(env) {
    def kubectlCmd = "kubectl" +
      " --kubeconfig=${kubeConfFolderPath}/config" +
      " --context=${env.kubeContext}" +
      " --namespace=default"

    def rollbackImage
    def rollBack = {
      script.stage("Rolling back deployment in ${env.name}") {
        script.container(kubectlContainer) {
          notifyRollingBack(env, rollbackImage)
          try {
            script.timeout(deploymentUpdateTimeout) {
              script.sh("${kubectlCmd} set image deployment/${kubernetesDeployment} ${kubernetesContainer}=${rollbackImage}")
              script.sh("${kubectlCmd} rollout status deployments ${kubernetesDeployment}")
            }
          } catch(e) {
            notifyRollbackFailed(env, rollbackImage)
            throw(e)
          }
        }
      }
    }

    script.stage("Deploying to ${env.name}") {
      script.container(kubectlContainer) {
        rollbackImage = getCurrentImage(kubectlCmd)
        notifyDeploying(env, rollbackImage)
        try {
          script.timeout(deploymentUpdateTimeout) {
            script.sh("${kubectlCmd} set image deployment/${kubernetesDeployment} ${kubernetesContainer}=${imageName}")
            script.sh("${kubectlCmd} rollout status deployments ${kubernetesDeployment}")
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

  private def runSmokes() {
    script.stage('Running smoke tests') {
      script.build('visitor_app_2_smoke')
    }
  }

  private def waitForValidationIn(env) {
    script.stage("Validation in ${env.name}") {
      script.input("Is the change OK in ${env.name}?")
    }
  }

  private def withRollbackManagement(Closure body) {
    def rollbacks = []
    def deploy = { env ->
      rollbacks = [deployEnv(env)] + rollbacks
    }
    def rollBack = {
      def exception
      rollbacks.each {
        try {
          it()
        } catch(e) {
          exception = e
          script.echo("Rollback failed with ${e}!")
          // Allow rest of the the rollbacks to also execute
        }
      }
      rollbacks = []
      if (exception) {
        // Re-throw the last exception, if there were any, to signal that the
        // job (including the rollbacks) didn't execute successfully.
        throw(exception)
      }
    }

    try {
      body(deploy, rollBack)
    } catch(e) {
      script.echo("Deploy either failed or was aborted. Rolling back changes in all affected environments.")
      rollBack()
      throw(e)
    }
  }
}
