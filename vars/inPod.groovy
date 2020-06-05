import org.yaml.snakeyaml.Yaml
import static com.salemove.Collections.addWithoutDuplicates

def call(Map args = [:], Closure body) {
  def defaultArgs = [
    cloud: 'CI',
    name: 'pipeline-build',
    containers: [agentContainer(image: 'jenkins/jnlp-slave:3.36-2-alpine')],
    inheritFrom: '',
    yaml: '''\
      apiVersion: v1
      kind: Pod
      spec:
        affinity:
          nodeAffinity:
            preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              preference:
                matchExpressions:
                - key: role
                  operator: In
                  values:
                  - jenkins
        tolerations:
        - key: dedicated
          value: jenkins
          operator: Equal
    '''.stripIndent()
  ]

  // For containers, add the lists together, but remove duplicates by name,
  // giving precedence to the user specified args.
  def finalContainers = addWithoutDuplicates((args.containers ?: []), defaultArgs.containers) { it.getArguments().name }

  def finalYaml = args.yaml ? addNodeSelectors(from: defaultArgs.yaml, to: args.yaml) : defaultArgs.yaml
  def finalArgs = defaultArgs << args << [containers: finalContainers, yaml: finalYaml]

  // Include a UUID to ensure that the label is unique for every build. This
  // way Jenkins will not re-use the pods for multiple builds and any changes
  // to the template will be guaranteed to to be picked up.
  def podLabel = "${finalArgs.name}-${UUID.randomUUID()}"

  podTemplate(finalArgs << [
    name: podLabel,
    label: podLabel,
  ]) {
    node(podLabel) {
      body()
    }
  }
}

private def addNodeSelectors(Map args) {
  def yaml = new Yaml()
  def resultMap = (Map) yaml.load(args.to)
  def fromMap = (Map) yaml.load(args.from)

  if (resultMap?.spec) {
    resultMap.spec.affinity = fromMap.spec.affinity
    resultMap.spec.tolerations = (resultMap.spec.tolerations ?: []) + fromMap.spec.tolerations
  } else if (resultMap) {
    resultMap.spec = fromMap.spec
  } else {
    resultMap = fromMap
  }

  yaml.dump(resultMap)
}
