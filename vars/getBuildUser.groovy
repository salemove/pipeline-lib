// Source: https://gist.github.com/jaysoncena/4b18951c3c5e68a9049ad340878761e9#gistcomment-3431750
// Credits to pere3 @ github
def call(currentBuild=currentBuild) {
    def userCause = currentBuild.rawBuild.getCause(Cause.UserIdCause)
    def upstreamCause = currentBuild.rawBuild.getCause(Cause.UpstreamCause)

    if (userCause) {
        userCause.getUserName()
    } else if (upstreamCause) {
        def upstreamJob = Jenkins.getInstance().getItemByFullName(upstreamCause.getUpstreamProject(), hudson.model.Job.class)
        if (upstreamJob) {
            def upstreamBuild = upstreamJob.getBuildByNumber(upstreamCause.getUpstreamBuild())
            if (upstreamBuild) {
                def realUpstreamCause = upstreamBuild.getCause(Cause.UserIdCause)
                if (realUpstreamCause) {
                    realUpstreamCause.getUserName()
                }
            }
        }
    }
}
