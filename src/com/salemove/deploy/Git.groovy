package com.salemove.deploy

class Git implements Serializable {
  private static final deployerSSHAgent = 'c5628152-9b4d-44ac-bd07-c3e2038b9d06'

  private def script
  Git(script) {
    this.script = script
  }

  def finishMerge() {
    ensureGitUsesSSH()
    script.sshagent([deployerSSHAgent]) {
      // And then push the merge commit to master, closing the PR
      script.sh('git push origin @:master')
      // Clean up by deleting the now-merged branch
      script.sh("git push origin --delete ${script.pullRequest.headRef}")
    }
  }

  def checkMasterHasNotChanged() {
    ensureGitUsesSSH()
    script.sshagent([deployerSSHAgent]) {
      script.sh('git fetch origin master')
    }
    // This exits with non-zero exit code and fails the build if remote master
    // is no longer fully included in the merge commit we're working with.
    // Pushing master in this state would fail anyway, so this gives us an
    // early exit in this case.
    try {
      script.sh('git merge-base --is-ancestor origin/master @')
    } catch(e) {
      script.echo('The master branch has changed between now and when the tests were run. Please start over.')
      throw(e)
    }
  }

  def getRepositoryName() {
    shEval('git remote get-url origin').replaceFirst(/^.*\/([^.]+)(\.git)?$/, '$1')
  }

  def getShortRevision() {
    shEval('git log -n 1 --pretty=format:\'%h\'')
  }

  def resetMergeCommitAuthor() {
    // Change commit author if merge commit is created by Jenkins
    def commitAuthor = shEval('git log -n 1 --pretty=format:\'%an\'')
    if (commitAuthor == 'Jenkins') {
      script.sh('git config user.name "sm-deployer"')
      script.sh('git config user.email "support@salemove.com"')
      script.sh('git commit --amend --no-edit --reset-author')
    }
  }

  // Make sure the remote uses a SSH URL. By default it's an HTTPS URL, which
  // when used to fetch or  push a commit, will require user input.
  private def ensureGitUsesSSH() {
    def httpsOriginURL = shEval('git remote get-url origin')
    def sshOriginURL = httpsOriginURL.replaceFirst(/https:\/\/github.com\//, 'git@github.com:')
    script.sh("git remote set-url origin ${sshOriginURL}")
  }

  private def shEval(String cmd) {
    def secureCmd = """\
    #!/bin/bash
    set -e
    set -o pipefail

    ${cmd}
    """
    script.sh(returnStdout: true, script: secureCmd).trim()
  }
}
