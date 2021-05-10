package com.salemove

class Communication {
  static final SLACK_RETRIES = 3

  static def safeSlackSend(script, Map args) {
    // Wrapper around Jenkins' own `slackSend` that will retry the sending process and finally ask for permission
    // to proceed with the pipeline w/o notifications.
    int retries = 0
    def slackResp

    while (retries < SLACK_RETRIES) {
      try {
        slackResp = script.slackSend([:] << args)

        if (!slackResp) {
          retries++
          script.echo("Got an empty response from Slack, retrying (${retries}/${SLACK_RETRIES})...")
        } else {
          break
        }
      } catch(e) {
        retries++
        script.echo("Caught an exception while trying to send a Slack message, retrying (${retries}/${SLACK_RETRIES})...")
      }
    }

    if (!slackResp) {
      if (script.env.CHANGE_ID) {
        // We're likely in a Pull Request
        def deployingUser = script.currentBuild.rawBuild.getCause(
            org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause
          ).userLogin
        def hereMDJobLink = "[here](${script.RUN_DISPLAY_URL}) (or [in the old UI](${script.BUILD_URL}/console))"

        try {
          script.pullRequest.comment("@${deployingUser}, Slack notifications failed, your input is required ${hereMDJobLink()}.")
        } catch(e) {
          script.echo("Failed to make a PR comment regarding user input.")
        }
      }

      script.input(
        "Could not send a notification to Slack. If Slack works as expected, click 'Abort'." +
        "You should only proceed without a Slack notification in case of an emergency."
      )
    } else {
      return slackResp
    }
  }
}
