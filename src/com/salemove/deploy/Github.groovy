package com.salemove.deploy

import static com.salemove.Collections.joinWithAnd

class Github implements Serializable {
  public static final deployStatusContext = 'continuous-integration/jenkins/pr-merge/deploy'
  public static final buildStatusContext = 'continuous-integration/jenkins/pr-merge'

  private def script, notify
  Github(script, Map args) {
    this.script = script
    this.notify = new Notify(script, args)
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

  def checkPRMergeable(Map args) {
    def problems = getStatusProblems() + getReviewProblems()
    if (problems.size() > 0) {
      script.echo("The PR is not ready to be merged! ${problems.join(' ')}")
      if (args.notifyOnInput) {
        notify.inputRequired()
      }
      script.input(
        'Are you sure you want to continue? If these problems persist and merging to master fails,' +
        ' then the changes will be rolled back in all environments, including production.'
      )
    }
  }

  private def getStatusProblems() {
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

    nonSuccessStatuses.collect { "Status ${it.context} is marked ${it.state}." }
  }

  private def getReviewProblems() {
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

    def problems = []
    if (changesRequestedReviews.size() > 0) {
      def users = changesRequestedReviews.collect { it.user }
      def plural = users.size() > 1
      problems << "User${plural ? 's' : ''} ${joinWithAnd(users)} ${plural ? 'have' : 'has'} requested changes."
    }
    if (approvedReviews.size() < 1) {
      problems << 'PR is not ready to be merged. At least one approval required.'
    }
    problems
  }
}
