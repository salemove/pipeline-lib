import static com.salemove.Collections.addWithoutDuplicates

def call(Map args = [:], Closure body) {
  def defaultArgs = [
    name: 'pipeline-docker-build',
    containers: [agentContainer(image: 'salemove/jenkins-agent-docker:19.03.15')],
    volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')],
    useBuildKit: false
  ]

  // For containers and volumes (list arguments), add the lists together, but
  // remove duplicates by name and mountPath respectively, giving precedence to
  // the user specified args.
  def finalContainers = addWithoutDuplicates((args.containers ?: []), defaultArgs.containers) { it.getArguments().name }
  def finalVolumes = addWithoutDuplicates((args.volumes ?: []), defaultArgs.volumes) { it.getArguments().mountPath }

  def finalArgs = defaultArgs << args << [
    containers: finalContainers,
    volumes: finalVolumes
  ]

  def useBuildKit = finalArgs.useBuildKit ? '1' : '0'
  finalArgs.remove('useBuildKit')

  inPod(finalArgs) {
    withEnv(["DOCKER_BUILDKIT=${useBuildKit}"]) {
      body()
    }
  }
}
