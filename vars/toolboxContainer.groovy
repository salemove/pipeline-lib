def call(Map args = [:]) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:b9636fc'
  ]

  interactiveContainer(defaultArgs << args)
}
