import static com.salemove.Collections.addWithoutDuplicates

def call(Map args = [:], Closure body) {
  def defaultArgs = [
    cloud: 'CI',
    name: 'pipeline-build',
    containers: [agentContainer(image: 'jenkins/jnlp-slave:alpine')]
  ]

  // For containers, add the lists together, but remove duplicates by name,
  // giving precedence to the user specified args.
  def finalContainers = addWithoutDuplicates((args.containers ?: []), defaultArgs.containers) { it.getArguments().name }

  def finalArgs = defaultArgs << args << [containers: finalContainers]

  // Include hashcode, because otherwise some changes to the template might not
  // get picked up
  def podLabel = "${finalArgs.name}-${finalArgs.hashCode()}"

  podTemplate(finalArgs << [
    name: podLabel,
    label: podLabel,
  ]) {
    node(podLabel) {
      body()
    }
  }
}
