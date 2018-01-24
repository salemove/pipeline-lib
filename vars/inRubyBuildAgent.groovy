def call(Map args = [:], Closure body) {
  def defaultArgs = [
    name: 'pipeline-ruby-build',
    containers: [
      agentContainer(image: 'salemove/jenkins-agent-ruby:2.4.1'),
      passiveContainer(
        name: 'db',
        image: 'postgres:9.5.7-alpine',
        envVars: [
          envVar(key: 'POSTGRES_USER', value: 'salemove'),
          envVar(key: 'POSTGRES_PASSWORD', value: 'salemovepass')
        ]
      ),
      passiveContainer(
        name: 'rabbitmq',
        image: 'rabbitmq:3.6.10-alpine'
      )
    ],
    volumes: [
      hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
      hostPathVolume(hostPath: '/mnt/ruby-build/', mountPath: '/usr/local/bundle')
    ]
  ]

  // For containers and volumes (list arguments), add the lists together, but
  // remove duplicates by name and mountPath respectively, giving precedence to
  // the user specified args.
  def finalContainers = (args.containers ?: []) + defaultArgs.containers
  finalContainers.unique { it.getArguments().name }

  def finalVolumes = (args.volumes ?: []) + defaultArgs.volumes
  finalVolumes.unique { it.getArguments().mountPath }

  def finalArgs = defaultArgs << args << [
    containers: finalContainers,
    volumes: finalVolumes
  ]

  inPod(finalArgs) {
    body()
  }
}
