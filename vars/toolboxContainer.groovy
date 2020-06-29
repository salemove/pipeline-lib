def call(Map args = [:]) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:9ac7119'
  ]

  interactiveContainer(defaultArgs << args)
}
