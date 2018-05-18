package com.salemove.deploy

class Github implements Serializable {
  public static final deployStatusContext = 'continuous-integration/jenkins/pr-merge/deploy'
  public static final buildStatusContext = 'continuous-integration/jenkins/pr-merge'

  private def script
  Github(script) {
    this.script = script
  }

  def setStatus(Map args) {
    def targetUrl = "${script.BUILD_URL}/console".toString()

    script.pullRequest.createStatus([
      context: buildStatusContext,
      targetUrl: targetUrl
    ] << args)
    script.pullRequest.createStatus([
      context: deployStatusContext,
      targetUrl: targetUrl
    ] << args)
  }

  def checkPRMergeable() {
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

    if (nonSuccessStatuses.size() > 0) {
      def statusMessages = nonSuccessStatuses.collect { "Status ${it.context} is marked ${it.state}." }
      script.error("Commit is not ready to be merged. ${statusMessages.join(' ')}")
    }
  }
}
