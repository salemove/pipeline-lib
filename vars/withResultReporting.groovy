def call(Map args = [:], Closure body) {
  def defaultArgs = [
    slackChannel: '#ci',
    mainBranch: 'master'
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
    def statusChanged = currentBuild.getPreviousBuild()?.result != currentResult
    if (statusChanged && BRANCH_NAME == finalArgs.mainBranch) {
      if (currentResult == 'SUCCESS') {
        slackSend(channel: finalArgs.slackChannel, color: 'good', message: "Success: ${JOB_NAME} ${BUILD_URL}")
      } else {
        slackSend(channel: finalArgs.slackChannel, color: 'danger', message: "Failure: ${JOB_NAME} ${BUILD_URL}")
      }
    }
  }
}
