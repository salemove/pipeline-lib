def call(Map args) {
  // Explicitly set command, args, and workingDir to null to use container defaults
  def defaultArgs = [
    ttyEnabled: false,
    command: null,
    args: null,
    workingDir: null
  ]

  containerTemplate(defaultArgs << args)
}
