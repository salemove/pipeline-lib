def call(Map args = [:]) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:dc01e55'
  ]

  interactiveContainer(defaultArgs << args)
}
