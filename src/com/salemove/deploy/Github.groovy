package com.salemove.deploy

import static com.salemove.Collections.joinWithAnd

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
    checkStatuses()
    checkReviews()
  }

  private def checkStatuses() {
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
      script.error("PR is not ready to be merged. ${statusMessages.join(' ')}")
    }
  }

  private def checkReviews() {
    def finalReviews = script.pullRequest.reviews
      // Include DISMISSED reviews in the search, to ensure previous APPROVED
      // or CHANGES_REQUESTED reviews by the same user are not counted in the
      // checks below.
      .findAll { ['CHANGES_REQUESTED', 'APPROVED', 'DISMISSED'].contains(it.state) }
      // groupBy + collect to find the last review submitted by any specific
      // user, as all reviews submitted by a user are included in the initial
      // list.
      .groupBy { it.user }
      .collect { user, reviews -> reviews.max { it.id } }

    def changesRequestedReviews = finalReviews.findAll { it.state == 'CHANGES_REQUESTED' }
    def approvedReviews = finalReviews.findAll { it.state == 'APPROVED' }

    if (changesRequestedReviews.size() > 0) {
      def users = changesRequestedReviews.collect { it.user }
      def plural = users.size() > 1
      script.error("PR is not ready to be merged. User${plural ? 's' : ''} ${joinWithAnd(users)} ${plural ? 'have' : 'has'} requested changes.")
    } else if (approvedReviews.size() < 1) {
      script.error('PR is not ready to be merged. At least one approval required.')
    }
  }
}
