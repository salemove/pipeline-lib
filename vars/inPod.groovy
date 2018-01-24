def call(Map args = [:], Closure body) {
  def defaultArgs = [
    cloud: 'CI',
    name: 'pipeline-build'
  ]

  def finalArgs = defaultArgs << args

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
