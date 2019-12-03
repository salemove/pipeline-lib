import groovy.json.JsonBuilder

def call(Map args = [:]) {
  def defaultArgs = [
    jenkinsVarsDir: 'jenkins-variables',
  ]

  def finalArgs = defaultArgs << args

  def registerAnsibleVars(jenkinsVarsDir) {
    echo("Loading registered variables from Ansible...")
    def vars = [:]
    varNames = sh(
      script: "ls ${jenkinsVarsDir}",
      returnStdout: true
      ).trim().split()
    varNames.each {
      vars.put(it, readFile("${jenkinsVarsDir}/${it}"))
    }
    def builder = new JsonBuilder()
    builder(vars)
    env.ansibleVarsJson = builder.toString()
  }

  ansiblePlaybook(args)
  registerAnsibleVars(finalArgs.jenkinsVarsDir)
}
