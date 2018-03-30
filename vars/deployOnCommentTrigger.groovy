import com.salemove.Deployer
import groovy.transform.Field

@Field deployerSSHAgent = 'c5628152-9b4d-44ac-bd07-c3e2038b9d06'
@Field dockerRegistryURI = '662491802882.dkr.ecr.us-east-1.amazonaws.com'
@Field dockerRegistryCredentialsID = 'ecr:us-east-1:ecr-docker-push'

def call(Map args) {
  def isDeployBuild = currentBuild.rawBuild.getCause(
    org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause
  ).asBoolean()

  if (isDeployBuild) {
    pullRequest.comment("Deploying. Follow progress [here](${RUN_DISPLAY_URL}) (or [in old UI](${BUILD_URL}/console))")
    try {
      def imageTag = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()

      echo("Publishing docker image ${args.image.imageName()} with tag ${imageTag}")
      docker.withRegistry("https://${dockerRegistryURI}", dockerRegistryCredentialsID) {
        args.image.push(imageTag)
      }

      echo("Deploying the image")
      new Deployer(this, args.subMap(['kubernetesDeployment', 'kubernetesContainer', 'inAcceptance']) + [
        imageName: "${dockerRegistryURI}/${args.image.id}:${imageTag}"
      ]).deploy()

      // Mark the current job's status as success, for the PR to be
      // mergeable.
      pullRequest.createStatus(
        status: 'success',
        context: 'continuous-integration/jenkins/pr-merge',
        description: 'The PR has successfully been deployed',
        targetUrl: BUILD_URL
      )
      // Mark a special deploy status as success, to indicate that the
      // job has also been successfully deployed.
      pullRequest.createStatus(
        status: 'success',
        context: 'continuous-integration/jenkins/pr-merge/deploy',
        description: 'The PR has successfully been deployed',
        targetUrl: BUILD_URL
      )

      sshagent([deployerSSHAgent]) {
        // Make sure the remote uses a SSH URL for the push to work. By
        // default it's an HTTPS URL, which when used to push a commit,
        // will require user input.
        def httpsOriginURL = sh(returnStdout: true, script: 'git remote get-url origin').trim()
        def sshOriginURL = httpsOriginURL.replaceFirst(/https:\/\/github.com\//, 'git@github.com:')
        sh("git remote set-url origin ${sshOriginURL}")

        // And then push the merge commit to master
        sh('git push origin @:master')
      }
    } catch(e) {
      pullRequest.comment("Deploy failed or was aborted. Check [the logs](${BUILD_URL}/console) and try again.")
      throw(e)
    }
  } else {
    echo('Build not triggered by !deploy comment. Not deploying')
  }
}
