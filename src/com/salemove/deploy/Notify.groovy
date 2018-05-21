package com.salemove.deploy

class Notify implements Serializable {
  private def script, kubernetesDeployment, kubernetesNamespace
  Notify(script, args) {
    this.script = script
    this.kubernetesDeployment = args.kubernetesDeployment
    this.kubernetesNamespace = args.kubernetesNamespace
  }

  def envDeployingForFirstTime(env, version) {
    sendSlack(env, [
      message: "${deployingUser()} is creating ${deployedResouce()} with version ${version}" +
        " in ${env.displayName}. This is the first deploy for this application."
    ])
  }
  def envDeployingVersionedForFirstTime(env, version) {
    sendSlack(env, [
      message: "${deployingUser()} is creating ${deployedResouce()} with version ${version}" +
        " in ${env.displayName}. This is the first versioned deploy for this application."
    ])
  }

  def envDeploying(env, version, rollbackVersion) {
    sendSlack(env, [
      message: "${deployingUser()} is updating ${deployedResouce()} to version ${version}" +
        " in ${env.displayName}. The current version is ${rollbackVersion}."
    ])
  }
  def envDeploySuccessful(env, version) {
    sendSlack(env, [
      color: 'good',
      message: "Successfully updated ${deployedResouce()} to version ${version}" +
        " in ${env.displayName}."
    ])
  }
  def envRollingBack(env, rollbackVersion) {
    sendSlack(env, [
      message: "Rolling back ${deployedResouce()} to version ${rollbackVersion}" +
        " in ${env.displayName}."
    ])
  }
  def envRollbackFailed(env, rollbackVersion) {
    sendSlack(env, [
      color: 'danger',
      message: "Failed to roll back ${deployedResouce()} to version ${rollbackVersion}" +
        " in ${env.displayName}. Manual intervention is required!"
    ])
  }
  def envDeletingDeploy(env) {
    sendSlack(env, [
      message: "Rolling back ${deployedResouce()} by deleting it in ${env.displayName}."
    ])
  }
  def envDeployDeletionFailed(env) {
    sendSlack(env, [
      color: 'danger',
      message: "Failed to roll back ${deployedResouce()} by deleting it" +
        " in ${env.displayName}. Manual intervention is required!"
    ])
  }
  def envUndoingDeploy(env) {
    sendSlack(env, [
      message: "Undoing update to ${deployedResouce()} in ${env.displayName}."
    ])
  }
  def envUndoFailed(env) {
    sendSlack(env, [
      color: 'danger',
      message: "Failed to undo update to ${deployedResouce()} in ${env.displayName}." +
        ' Manual intervention is required!'
    ])
  }

  def deployFailedOrAborted() {
    script.pullRequest.comment(
      "Deploy failed or was aborted. @${deployingUser()}, please check the logs ${hereMDJobLink()}."
    )
  }
  def inputRequired() {
    script.pullRequest.comment("@${deployingUser()}, your input is required ${hereMDJobLink()}.")
  }
  def inputRequiredPostAcceptanceValidation() {
    script.pullRequest.comment(
      "@${deployingUser()}, the changes were validated in acceptance. Please click **Proceed**" +
      " ${hereMDJobLink()} to continue the deployment."
    )
  }

  private def sendSlack(env, Map args) {
    // The << operator mutates the left-hand map. Start with an empty map ([:])
    // to avoid mutating user-provided object.
    script.slackSend([:] << args << [
      channel: env.slackChannel,
      message: "${args.message}" +
        "\n<${script.pullRequest.url}|PR ${script.pullRequest.number} - ${script.pullRequest.title}>" +
        "\nOpen in <${script.RUN_DISPLAY_URL}|Blue Ocean> or <${script.BUILD_URL}/console|Old UI>"
    ])
  }

  private def deployingUser() {
    script.currentBuild.rawBuild.getCause(
      org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause
    ).userLogin
  }

  // A Markdown string for linking to the job in e.g. GitHub comments.
  private def hereMDJobLink() {
    "[here](${script.RUN_DISPLAY_URL}) (or [in the old UI](${script.BUILD_URL}/console))"
  }

  private def deployedResouce() {
    "deployment/${kubernetesDeployment}" +
      (kubernetesNamespace == 'default' ? '' : " in ${kubernetesNamespace} namespace")
  }
}
