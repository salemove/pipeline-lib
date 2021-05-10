import jenkins.model.*
import static com.salemove.Communication.safeSlackSend

def call(Map args = [:], Closure body) {
  def defaultArgs = [
    slackChannel: '#ci',
    mainBranch: 'master',
    strategy: 'onMainBranchChange',
    maxLogLines: 250
  ]
  def finalArgs = defaultArgs << args

  // Recursive closure getting the result of the last completed build that is either
  // SUCCESS or FAILURE (which means, builds with ABORTED or UNSTABLE are ignored)
  def retrieveLastResult
  retrieveLastResult = { build ->
    def previousBuild = build?.previousBuild
    if (previousBuild) {
      def result = previousBuild.result
      if (result in ['SUCCESS', 'FAILURE']) {
        result
      } else {
        retrieveLastResult(previousBuild)
      }
    }
  }
  def mailSend = { mailArgs ->
    if (!mailArgs.to) {
      echo("Email not specified, skipping email notification.")
      return
    }
    def from = JenkinsLocationConfiguration.get().getAdminAddress()
    def buildId = "${env.JOB_NAME} ${currentBuild.displayName}"
    def mailBody = "${env.BUILD_URL}"
    if (mailArgs.log) {
      def consoleLog = currentBuild.rawBuild.getLog(finalArgs.maxLogLines).join('<br>')
      mailBody = "${mailBody}<br><br>${consoleLog}"
    }
    mail(
      from: from,
      to: mailArgs.to,
      subject: "${mailArgs.message}: ${buildId}",
      body: mailBody,
      mimeType: 'text/html',
      cc: '',
      bcc: '',
      replyTo: ''
    )
  }

  def buildDescription = "${JOB_NAME} (Open <${RUN_DISPLAY_URL}|Blue Ocean> or <${BUILD_URL}|Old UI>)"
  if (finalArgs.customMessage) {
    buildDescription += "\n${finalArgs.customMessage}"
  }

  def threadId
  def sendStartMessage = { ->
    if (finalArgs.strategy == 'always') {
      def resp = safeSlackSend(this, [channel: finalArgs.slackChannel, message: "Started: ${buildDescription}"])
      threadId = resp.threadId
    }
  }

  try {
    sendStartMessage()
    body()
  } catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
  } finally {
    // currentBuild.result of null indicates success.
    def currentResult = currentBuild.result ?: 'SUCCESS'
    def statusChanged = retrieveLastResult(currentBuild) != currentResult

    switch(finalArgs.strategy) {
      case 'onMainBranchChange':
        if (statusChanged && env.BRANCH_NAME == finalArgs.mainBranch) {
          if (currentResult == 'SUCCESS') {
            safeSlackSend(this, [channel: finalArgs.slackChannel, color: 'good', message: "Success: ${buildDescription}"])
            mailSend(to: finalArgs.mailto, message: "Jenkins build is back to normal")
          } else {
            safeSlackSend(this, [channel: finalArgs.slackChannel, color: 'danger', message: "Failure: ${buildDescription}"])
            mailSend(to: finalArgs.mailto, message: "Build failed in Jenkins", log: true)
          }
        }
        break
      case 'onFailure':
        if (currentResult != 'SUCCESS') {
          safeSlackSend(this, [channel: finalArgs.slackChannel, color: 'danger', message: "Failure: ${buildDescription}"])
          mailSend(to: finalArgs.mailto, message: "Build failed in Jenkins", log: true)
        }
        break
      case 'onFailureAndRecovery':
        if (currentResult == 'SUCCESS') {
          if (statusChanged) {
            safeSlackSend(this, [channel: finalArgs.slackChannel, color: 'good', message: "Success: ${buildDescription}"])
            mailSend(to: finalArgs.mailto, message: "Jenkins build is back to normal")
          }
        } else {
          safeSlackSend(this, [channel: finalArgs.slackChannel, color: 'danger', message: "Failure: ${buildDescription}"])
          mailSend(to: finalArgs.mailto, message: "Build failed in Jenkins", log: true)
        }
        break
      case 'always':
        if (currentResult == 'SUCCESS') {
          safeSlackSend(this, [channel: threadId, color: 'good', message: "Success: ${buildDescription}"])
          mailSend(to: finalArgs.mailto, message: "Jenkins build successful")
        } else {
          safeSlackSend(this, [channel: threadId, color: 'danger', replyBroadcast: true, message: "Failure: ${buildDescription}"])
          mailSend(to: finalArgs.mailto, message: "Build failed in Jenkins", log: true)
        }
        break
      default:
        error('Invalid strategy specified for withResultReporting')
        break
    }
  }
}
