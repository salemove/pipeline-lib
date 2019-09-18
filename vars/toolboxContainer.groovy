def call(Map args) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:2be721c'
  ]

  interactiveContainer(defaultArgs << args)
}
