def Deployer, Github
node {
  checkout(scm)
  def version = sh(script: 'git log -n 1 --pretty=format:\'%h\'', returnStdout: true).trim()
  // Load library from currently checked out code. Also loads global vars
  // in addition to the Deployer class assigned here.
  def salemove = library(identifier: "pipeline-lib@${version}", retriever: legacySCM(scm)).com.salemove
  Deployer = salemove.Deployer
  Github = salemove.deploy.Github
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
def expectedBuildValue = { version -> version }
def expectedTemplateValue = { version, envName -> "${envName}-${version}" }
def expectedResponse = { buildVersion, deployVersion, envName ->
  "BUILD_VALUE=${expectedBuildValue(buildVersion)}" +
  ", TEMPLATE_VALUE=${expectedTemplateValue(deployVersion, envName)}"
}

properties(deployer.wrapProperties())

withResultReporting(slackChannel: '#tm-is') {
  inDockerAgent(deployer.wrapPodTemplate()) {
    def image, buildVersion
    stage('Build') {
      checkout(scm)
      buildVersion = sh(script: 'git log -n 1 --pretty=format:\'%h\'', returnStdout: true).trim()
      image = docker.build(projectName, "--build-arg 'BUILD_VALUE=${buildVersion}' test")
    }

    deployer.deployOnCommentTrigger(
      image: image,
      kubernetesNamespace: 'default',
      kubernetesDeployment: projectName,
      // inAcceptance is deprecated, but is left here to test backwards
      // compatibility
      inAcceptance: {
        def deployVersion = sh(script: 'git log -n 1 --pretty=format:\'%h\'', returnStdout: true).trim()
        def response = actualResponse('acceptance', 'at.samo.io')
        def expectation = expectedResponse(buildVersion, deployVersion, 'acceptance')

        if (response != expectation) {
          error("Expected response to be \"${expectation}\", but was \"${response}\"")
        }
      },
      automaticChecksFor: { env ->
        def deployVersion = sh(script: 'git log -n 1 --pretty=format:\'%h\'', returnStdout: true).trim()
        env['runInKube'](
          command: './test.sh',
          overwriteEntrypoint: true,
          additionalArgs: "--env='BUILD_VALUE=${expectedBuildValue(buildVersion)}'" +
            " --env='TEMPLATE_VALUE=${expectedTemplateValue(deployVersion, env.name)}'"
        )
      },
      checklistFor: { env ->
        [[
          name: 'OK?',
          description: "Are you feeling good about this change in ${env.name}? https://app.${env.domainName}"
        ]]
      }
    )

    def isPRBuild = !!env.CHANGE_ID
    if (isPRBuild && !Deployer.isDeploy(this)) {
      pullRequest.createStatus(
        status: 'success',
        context: Github.deployStatusContext,
        description: 'PRs in this project don\'t have to necessarily be deployed',
        targetUrl: BUILD_URL
      )
    }
  }
}
