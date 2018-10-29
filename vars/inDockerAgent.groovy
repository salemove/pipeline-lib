import static com.salemove.Collections.addWithoutDuplicates

def call(Map args = [:], Closure body) {
  def defaultArgs = [
    name: 'pipeline-docker-build',
    containers: [agentContainer(image: 'salemove/jenkins-agent-docker:17.12.0-0dc8e6a')],
    volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
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

  inPod(finalArgs) {
    body()
  }
}
