import groovy.json.JsonBuilder

def call(Map args = [:], String jenkinsVarsDir = 'jenkins-variables') {
  ansiblePlaybook(args)

  echo("Loading registered variables from Ansible...")
  def vars = [:]
  varNames = sh(
    script: "ls ${jenkinsVarsDir} 2>/dev/null || true",
    returnStdout: true
  ).trim().split()
  varNames.each {
    vars.put(it, readFile("${jenkinsVarsDir}/${it}"))
  }

  def builder = new JsonBuilder()
  builder(vars)
  env.ansibleVarsJson = builder.toString()
}
