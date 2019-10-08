package com.salemove

class Datadog {
  public static final podYAML = '''\
    apiVersion: v1
    kind: Pod
    spec:
      containers:
      # Sidecar for forwarding localhost:8125 requests to a dd-agent running on
      # the current node.
      - name: dd-agent-forwarder
        image: alpine/socat:1.0.5
        resources:
          limits:
            cpu: "50m"
            memory: "20Mi"
          requests:
            cpu: "10m"
            memory: "10Mi"
        args: ["UDP-RECVFROM:8125,fork", "UNIX-SENDTO:/var/run/datadog/dsd.socket"]
        volumeMounts:
        - name: dsdsocket
          mountPath: /var/run/datadog
          readOnly: true
      volumes:
      - name: dsdsocket
        hostPath:
          path: /var/run/datadog
  '''.stripIndent()

  private def script, staticTags
  Datadog(script, Map args = [:]) {
    this.script = script
    def defaultArgs = [
      tags: []
    ]
    def finalArgs = defaultArgs << args
    staticTags = finalArgs.tags
  }

  def sendMetric(Map args) {
    def defaultArgs = [
      tags: []
    ]
    def finalArgs = defaultArgs << args
    def finalTags = (staticTags + finalArgs.tags)

    def datagram = "jenkins.${finalArgs.nameSuffix}" +
      ":${finalArgs.value}" +
      "|${finalArgs.type}" +
      (finalTags ? "|#${finalTags.join(',')}" : '')

    script.sh("echo -n '${datagram}' | nc -u -w1 127.0.0.1 8125")
  }

  def sendDuration(Map args) {
    sendMetric(args << [
      type: 'h', // histogram
      value: timeDiffMillis(args.start, args.end)
    ])
  }

  @NonCPS
  private def timeDiffMillis(Date start, Date end) {
    use(groovy.time.TimeCategory) {
      def duration = end - start
      return duration.millis
    }
  }
}
