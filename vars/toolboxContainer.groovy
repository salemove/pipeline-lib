def call(Map args = [:]) {
  def defaultArgs = [
    name: 'toolbox',
    image: 'salemove/jenkins-toolbox:aeeaac4'
  ]

  interactiveContainer(defaultArgs << args)
}
