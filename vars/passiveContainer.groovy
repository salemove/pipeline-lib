def call(Map args) {
  def defaultArgs = [
    ttyEnabled: false,
    command: null,
    args: null // Explicitly set command and args to null to use default container entrypoint
  ]

  containerTemplate(defaultArgs << args)
}
