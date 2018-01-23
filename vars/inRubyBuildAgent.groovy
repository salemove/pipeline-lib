def call(Map args = [:], Closure body) {
  def defaultArgs = [
    cloud: 'CI',
    containers: [
      containerTemplate(
        name: 'jnlp',
        image: 'salemove/jenkins-agent-ruby:2.4.1',
        workingDir: '/root', // Required for docker.withRegistry to work
        ttyEnabled: true
      ),
      containerTemplate(
        name: 'db',
        image: 'postgres:9.5.7-alpine',
        ttyEnabled: false,
        command: null, args: null, // Explicitly set command and args to null to use default container entrypoint
        envVars: [
          envVar(key: 'POSTGRES_USER', value: 'salemove'),
          envVar(key: 'POSTGRES_PASSWORD', value: 'salemovepass')
        ]
      ),
      containerTemplate(
        name: 'rabbitmq',
        image: 'rabbitmq:3.6.10-alpine',
        ttyEnabled: false,
        command: null, args: null // Explicitly set command and args to null to use default container entrypoint
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

  // Include hashcode, because otherwise some changes to the template might not
  // get picked up
  def podLabel = "pipeline-ruby-build-${finalArgs.hashCode()}"

  podTemplate(finalArgs << [
    name: podLabel,
    label: podLabel,
  ]) {
    node(podLabel) {
      body()
    }
  }
}
