package com.salemove.deploy

import com.salemove.deploy.Args
import static com.salemove.Communication.safeSlackSend

class Notify implements Serializable {
  private def script, kubernetesDeployment, kubernetesNamespace, threadIds
  Notify(script, args) {
    this.script = script
    this.kubernetesDeployment = args.kubernetesDeployment
    this.kubernetesNamespace = args.kubernetesNamespace
    this.threadIds = [:]
  }

  def envDeployingForFirstTime(env, version) {
    sendSlack(env.slackChannel, [
      message: "${deployingUser()} is creating ${deployedResouce()} with version `${version}`" +
        " in ${env.displayName}. This is the first deploy for this application."
    ])
  }
  def envDeployingVersionedForFirstTime(env, version) {
    sendSlack(env.slackChannel, [
      message: "${deployingUser()} is creating ${deployedResouce()} with version `${version}`" +
        " in ${env.displayName}. This is the first versioned deploy for this application."
    ])
  }

  def prodDeploying(version) {
    sendSlack('#production', [
      message: "${deployingUser()} is updating ${deployedResouce()} to version `${version}`" +
        ' in production.'
    ])
  }
  def envDeploying(env, version, rollbackVersion, repository) {
    def rollbackLink = env.name == 'acceptance' ?  '' :
      " (<${rollbackURL(env, rollbackVersion, repository)}|roll back>)"

    sendSlack(env.slackChannel, [
      message: "${deployingUser()} is updating ${deployedResouce()} to version `${version}`" +
        " in ${env.displayName}. The current version is `${rollbackVersion}`${rollbackLink}."
    ])
  }
  def envDeploySuccessful(env, version) {
    sendSlack(env.slackChannel, [
      color: 'good',
      message: "Successfully updated ${deployedResouce()} to version `${version}`" +
        " in ${env.displayName}."
    ])
  }
  def envRollingBack(env, rollbackVersion) {
    sendSlack(env.slackChannel, [
      message: "Rolling back ${deployedResouce()} to version `${rollbackVersion}`" +
        " in ${env.displayName}."
    ])
  }
  def envRollbackFailed(env, rollbackVersion) {
    sendSlack(env.slackChannel, [
      color: 'danger',
      replyBroadcast: true,
      message: "Failed to roll back ${deployedResouce()} to version `${rollbackVersion}`" +
        " in ${env.displayName}. Manual intervention is required!"
    ])
  }
  def envDeletingDeploy(env) {
    sendSlack(env.slackChannel, [
      message: "Rolling back ${deployedResouce()} by deleting it in ${env.displayName}."
    ])
  }
  def envDeployDeletionFailed(env) {
    sendSlack(env.slackChannel, [
      color: 'danger',
      replyBroadcast: true,
      message: "Failed to roll back ${deployedResouce()} by deleting it" +
        " in ${env.displayName}. Manual intervention is required!"
    ])
  }
  def envUndoingDeploy(env) {
    sendSlack(env.slackChannel, [
      message: "Undoing update to ${deployedResouce()} in ${env.displayName}."
    ])
  }
  def envUndoFailed(env) {
    sendSlack(env.slackChannel, [
      color: 'danger',
      replyBroadcast: true,
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
  def inputRequiredInBeta() {
    script.pullRequest.comment(
      "@${deployingUser()}, the changes have been deployed to beta. Please continue ${hereMDJobLink()}."
    )
  }
  def unexpectedArgs() {
    script.pullRequest.comment(
      "Sorry, I don't understand. I only support the '${Args.noGlobalLock}' argument." +
      " Check the logs ${hereMDJobLink()} for more information."
    )
  }

  private def sendSlack(slackChannel, Map args) {
    // The << operator mutates the left-hand map. Start with an empty map ([:])
    // to avoid mutating user-provided object.
    def resp = safeSlackSend(script, [:] << args << [
      channel: threadIds[slackChannel] ?: slackChannel,
      message: "${args.message}" +
        "\n<${script.pullRequest.url}|PR ${script.pullRequest.number} - ${script.pullRequest.title}>" +
        "\nOpen in <${script.RUN_DISPLAY_URL}|Blue Ocean> or <${script.BUILD_URL}/console|Old UI>"
    ])
    if (!threadIds[slackChannel]) {
      threadIds[slackChannel] = resp.threadId
    }
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
    "`deploy/${kubernetesDeployment}`" +
      (kubernetesNamespace == 'default' ? '' : " in ${kubernetesNamespace} namespace")
  }

  private def rollbackURL(env, rollbackVersion, repository) {
    'https://jenkins.salemove.com/job/deploy/job/application/parambuild/' +
    "?repository=${repository}" +
    "&application=${kubernetesDeployment}" +
    "&version=${rollbackVersion}" +
    "&environment=${env.kubeEnvName}"
  }
}
