def Deployer
node {
  checkout(scm)
  def version = sh(script: 'git log -n 1 --pretty=format:\'%h\'', returnStdout: true).trim()
  // Load library from currently checked out code. Also loads global vars
  // in addition to the Deployer class assigned here.
  Deployer = library(identifier: "pipeline-lib@${version}", retriever: legacySCM(scm))
    .com.salemove.Deployer
}

def projectName = 'deploy-pipeline-test'

def actualResponse = { envName, domainName ->
  def response
  container('deployer-container') {
    response = sh(
      script: "curl -H 'Host: ${projectName}.${domainName}' gateway.${domainName}",
      returnStdout: true
    ).trim()
  }
  response
}
def expectedResponse = { version, envName ->
  "BUILD_VALUE=${version}, TEMPLATE_VALUE=${envName}-${version}"
}

properties(deployer.wrapProperties())

withResultReporting(slackChannel: '#tm-is') {
  inDockerAgent(deployer.wrapPodTemplate()) {
    def image, version
    stage('Build') {
      checkout(scm)
      version = sh(script: 'git log -n 1 --pretty=format:\'%h\'', returnStdout: true).trim()
      image = docker.build(projectName, "--build-arg 'BUILD_VALUE=${version}' test")
    }

    deployer.deployOnCommentTrigger(
      image: image,
      kubernetesDeployment: projectName,
      inAcceptance: {
        def response = actualResponse('acceptance', 'at.samo.io')
        def expectation = expectedResponse(version, 'acceptance')

        if (response != expectation) {
          error("Expected response to be \"${expectation}\", but was \"${response}\"")
        }
      },
      checklistFor: { env ->
        [[
          name: 'curl',
          description: "curl response \"${actualResponse(env.name, env.domainName)}\"" +
            " matches expected \"${expectedResponse(version, env.name)}\""
        ]]
      }
    )

    def isPRBuild = !!env.CHANGE_ID
    if (isPRBuild && !Deployer.isDeploy(this)) {
      pullRequest.createStatus(
        status: 'success',
        context: Deployer.deployStatusContext,
        description: 'PRs in this project don\'t have to necessarily be deployed',
        targetUrl: BUILD_URL
      )
    }
  }
}
