def call(Map args) {
  def defaultArgs = [
    workingDir: '/root', // Has to be the same with the "jnlp" (agent) container
    ttyEnabled: true,
    command: '/bin/sh -c',
    args: 'cat' // A command that doesn't exit
  ]

  containerTemplate(defaultArgs << args)
}
