import groovy.json.JsonSlurperClassic

def call(Map args = [:]) {

  def buildResult = build(args)
  def buildVars = buildResult.getBuildVariables()
  def ansibleVarsJson = buildVars.ansibleVarsJson
  assert ansibleVarsJson: "No results found - make sure to call ansiblePlaybookWithResults"

  def slurper = new JsonSlurperClassic()
  def ansibleVars = slurper.parseText(ansibleVarsJson)
  return [buildResult, ansibleVars]
}
