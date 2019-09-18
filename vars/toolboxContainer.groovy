def call(Map args) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:a99ffb7'
  ]

  interactiveContainer(defaultArgs << args)
}
