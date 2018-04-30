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

  // Include a UUID to ensure that the label is unique for every build. This
  // way Jenkins will not re-use the pods for multiple builds and any changes
  // to the template will be guaranteed to to be picked up.
  def podLabel = "${finalArgs.name}-${UUID.randomUUID()}"

  podTemplate(finalArgs << [
    name: podLabel,
    label: podLabel,
  ]) {
    node(podLabel) {
      body()
    }
  }
}
