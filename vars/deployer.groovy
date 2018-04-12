import static com.salemove.Collections.addWithoutDuplicates
import com.salemove.Deployer
import groovy.transform.Field

@Field deployerSSHAgent = 'c5628152-9b4d-44ac-bd07-c3e2038b9d06'
@Field dockerRegistryURI = 'https://662491802882.dkr.ecr.us-east-1.amazonaws.com'
@Field dockerRegistryCredentialsID = 'ecr:us-east-1:ecr-docker-push'

def wrapPodTemplate(Map args = [:]) {
  // For containers and volumes, add the lists together, but remove duplicates
  // by name and mountPath respectively, giving precedence to the user
  // specified args.
  args + [
    containers: addWithoutDuplicates((args.containers ?: []), Deployer.containers(this)) { it.getArguments().name },
    volumes: addWithoutDuplicates((args.volumes ?: []), Deployer.volumes(this)) { it.getArguments().mountPath }
  ]
}

def wrapProperties(providedProperties = []) {
  def isPRBuild = !!env.CHANGE_ID
  if (isPRBuild) {
    // Mark a special deploy status as pending, to indicate as soon as possible,
    // that this project now uses branch deploys and shouldn't be merged without
    // deploying.
    pullRequest.createStatus(
      status: 'pending',
      context: Deployer.deployStatusContext,
      description: 'The PR shouldn\'t be merged before it\'s deployed.',
      targetUrl: BUILD_URL
    )
  }

  def isDeploy = Deployer.isDeploy(this)
  if (isDeploy) {
    // Stop all previous builds that are still in progress
    while(currentBuild.rawBuild.getPreviousBuildInProgress() != null) {
      echo("Stopping ${currentBuild.rawBuild.getPreviousBuildInProgress()?.getAbsoluteUrl()}")
      currentBuild.rawBuild.getPreviousBuildInProgress()?.doStop()
      // Give the job some time to finish before trying again
      sleep(time: 10, unit: 'SECONDS')
    }
  }

  def tags = [
    "is_deploy=${isDeploy}",
    // Remove PR number or branch name suffix from the job name
    "project=${JOB_NAME.replaceFirst(/\/[^\/]+$/, '')}"
  ]

  providedProperties + [
    pipelineTriggers([issueCommentTrigger(Deployer.triggerPattern)]),
    [
      $class: 'DatadogJobProperty',
      tagProperties: tags.join("\n")
    ]
  ]
}

def deployOnCommentTrigger(Map args) {
  if (!Deployer.isDeploy(this)) {
    echo("Build not triggered by ${Deployer.triggerPattern} comment. Not deploying")
    return
  }

  try {
    def nonSuccessStatuses = pullRequest.statuses
      // Ignore statuses that are managed by this build. They're expected to be
      // 'pending' at this point.
      .findAll { it.context != Deployer.deployStatusContext && it.context != Deployer.buildStatusContext }
      // groupBy + collect to reduce multiple pending statuses + success status
      // to a single success status. For non-success statuses, if there are
      // many different states, use the last one.
      .groupBy { it.context }
      .collect { context, statuses ->
        statuses.inject { finalStatus, status -> finalStatus.state == 'success' ? finalStatus : status }
      }
      .findAll { it.state != 'success' }

    if (!nonSuccessStatuses.empty) {
      def statusMessages = nonSuccessStatuses.collect { "Status ${it.context} is marked ${it.state}." }
      error("Commit is not ready to be merged. ${statusMessages.join(' ')}")
    }

    def version = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()

    echo("Publishing docker image ${args.image.imageName()} with tag ${version}")
    docker.withRegistry(dockerRegistryURI, dockerRegistryCredentialsID) {
      args.image.push(version)
    }

    echo("Deploying the image")
    new Deployer(this, args.subMap(['kubernetesDeployment', 'inAcceptance']) + [
      version: version
    ]).deploy()

    // Mark the current job's status as success, for the PR to be
    // mergeable.
    pullRequest.createStatus(
      status: 'success',
      context: Deployer.buildStatusContext,
      description: 'The PR has successfully been deployed',
      targetUrl: BUILD_URL
    )
    // Mark a special deploy status as success, to indicate that the
    // job has also been successfully deployed.
    pullRequest.createStatus(
      status: 'success',
      context: Deployer.deployStatusContext,
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

      // And then push the merge commit to master, closing the PR
      sh('git push origin @:master')
      // Clean up by deleting the now-merged branch
      sh("git push origin --delete ${pullRequest.headRef}")
    }
  } catch(e) {
    pullRequest.createStatus(
      status: 'failure',
      context: Deployer.deployStatusContext,
      description: 'Deploy either failed or was aborted',
      targetUrl: BUILD_URL
    )
    pullRequest.comment(
      "Deploy failed or was aborted. @${Deployer.deployingUser(this)}, " +
      "please check [the logs](${BUILD_URL}/console) and try again."
    )
    throw(e)
  }
}
