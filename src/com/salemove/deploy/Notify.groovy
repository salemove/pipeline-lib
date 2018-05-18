package com.salemove.deploy

class Notify implements Serializable {
  private def script, kubernetesDeployment
  Notify(script, args) {
    this.script = script
    this.kubernetesDeployment = args.kubernetesDeployment
  }

  def envDeployingForFirstTime(env, version) {
    script.slackSend(
      channel: env.slackChannel,
      message: "${deployingUser()} is creating deployment/${kubernetesDeployment} with" +
        " version ${version} in ${env.displayName}. This is the first deploy for this application." +
        " <${script.pullRequest.url}|PR ${script.pullRequest.number} - ${script.pullRequest.title}>"
    )
  }
  def envDeployingVersionedForFirstTime(env, version) {
    script.slackSend(
      channel: env.slackChannel,
      message: "${deployingUser()} is creating deployment/${kubernetesDeployment} with" +
        " version ${version} in ${env.displayName}. This is the first versioned deploy for this application." +
        " <${script.pullRequest.url}|PR ${script.pullRequest.number} - ${script.pullRequest.title}>"
    )
  }

  def envDeploying(env, version, rollbackVersion) {
    script.slackSend(
      channel: env.slackChannel,
      message: "${deployingUser()} is updating deployment/${kubernetesDeployment} to" +
        " version ${version} in ${env.displayName}. The current version is ${rollbackVersion}." +
        " <${script.pullRequest.url}|PR ${script.pullRequest.number} - ${script.pullRequest.title}>"
    )
  }
  def envDeploySuccessful(env, version) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'good',
      message: "Successfully updated deployment/${kubernetesDeployment} to version ${version}" +
        " in ${env.displayName}."
    )
  }
  def envRollingBack(env, rollbackVersion) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Rolling back deployment/${kubernetesDeployment} to version ${rollbackVersion}" +
        " in ${env.displayName}."
    )
  }
  def envRollbackFailed(env, rollbackVersion) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'danger',
      message: "Failed to roll back deployment/${kubernetesDeployment} to version ${rollbackVersion}" +
        " in ${env.displayName}. Manual intervention is required!"
    )
  }
  def envDeletingDeploy(env) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Rolling back deployment/${kubernetesDeployment} by deleting it in ${env.displayName}."
    )
  }
  def envDeployDeletionFailed(env) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'danger',
      message: "Failed to roll back deployment/${kubernetesDeployment} by deleting it" +
        " in ${env.displayName}. Manual intervention is required!"
    )
  }
  def envUndoingDeploy(env) {
    script.slackSend(
      channel: env.slackChannel,
      message: "Undoing update to deployment/${kubernetesDeployment} in ${env.displayName}."
    )
  }
  def envUndoFailed(env) {
    script.slackSend(
      channel: env.slackChannel,
      color: 'danger',
      message: "Failed to undo update to deployment/${kubernetesDeployment} in ${env.displayName}." +
        ' Manual intervention is required!'
    )
  }

  def deployFailedOrAborted() {
    script.pullRequest.comment(
      "Deploy failed or was aborted. @${deployingUser()}, " +
      "please check [the logs](${script.BUILD_URL}/console) and try again."
    )
  }
  def inputRequired() {
    script.pullRequest.comment(
      "@${deployingUser()}, your input is required [here](${script.RUN_DISPLAY_URL}) " +
      "(or [in the old UI](${script.BUILD_URL}/console))."
    )
  }
  def inputRequiredPostAcceptanceValidation() {
    script.pullRequest.comment(
      "@${deployingUser()}, the changes were validated in acceptance. Please click **Proceed** " +
      "[here](${script.RUN_DISPLAY_URL}) (or [in the old UI](${script.BUILD_URL}/console)) to " +
      'continue the deployment.'
    )
  }

  private def deployingUser() {
    script.currentBuild.rawBuild.getCause(
      org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause
    ).userLogin
  }
}
