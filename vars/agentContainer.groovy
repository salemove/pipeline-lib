def call(Map args) {
  def defaultArgs = [
    name: 'jnlp',
    workingDir: '/root', // Required for working with docker
    ttyEnabled: true,
    command: null,
    args: null // Explicitly set command and args to null to use default container entrypoint
  ]

  containerTemplate(defaultArgs << args)
}
