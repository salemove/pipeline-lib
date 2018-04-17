def call(Map args = [:], Closure body) {
  def defaultArgs = [
    slackChannel: '#ci',
    mainBranch: 'master',
    strategy: 'onMainBranchChange'
  ]
  def finalArgs = defaultArgs << args

  try {
    body()
  } catch (e) {
    currentBuild.result = 'FAILED'
    throw e
  } finally {
    // currentBuild.result of null indicates success.
    def currentResult = currentBuild.result ?: 'SUCCESS'

    def buildDescription = "${JOB_NAME} ${BUILD_URL}"

    switch(finalArgs.strategy) {
      case 'onMainBranchChange':
        def statusChanged = currentBuild.getPreviousBuild()?.result != currentResult
        if (statusChanged && BRANCH_NAME == finalArgs.mainBranch) {
          if (currentResult == 'SUCCESS') {
            slackSend(channel: finalArgs.slackChannel, color: 'good', message: "Success: ${buildDescription}")
          } else {
            slackSend(channel: finalArgs.slackChannel, color: 'danger', message: "Failure: ${buildDescription}")
          }
        }
        break
      case 'onFailure':
        if (currentResult != 'SUCCESS') {
          slackSend(channel: finalArgs.slackChannel, color: 'danger', message: "Failure: ${buildDescription}")
        }
        break
      default:
        error('Invalid strategy specified for withResultReporting')
        break
    }
  }
}
