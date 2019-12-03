import groovy.json.JsonSlurperClassic

def call(Map args = [:]) {

  def getAnsibleVars(buildResult) {
    def buildVars = buildResult.getBuildVariables()
    def ansibleVarsJson = buildVars.ansibleVarsJson
    assert ansibleVarsJson: "No results found - make sure to call ansiblePlaybookWithResults"
    def slurper = new JsonSlurperClassic()
    return slurper.parseText(ansibleVarsJson)
  }

  def buildResult = build(args)
  def ansibleVars = getAnsibleVars(buildResult)
  return [buildResult, ansibleVars]
}
