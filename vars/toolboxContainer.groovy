def call(Map args = [:]) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:769f4eb'
  ]

  interactiveContainer(defaultArgs << args)
}
