import static com.salemove.Collections.addWithoutDuplicates
import com.salemove.Deployer
import com.salemove.deploy.Github

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
      context: Github.deployStatusContext,
      description: 'The PR shouldn\'t be merged before it\'s deployed.',
      targetUrl: "${BUILD_URL}/console".toString()
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

  new Deployer(this, args).deploy()
}
