# Funciones comunes para las políticas de Kubernetes: extraen el pod spec y los contenedores de cualquier
# workload, y resuelven las excepciones documentadas (policy/data/exceptions.yaml).
package lib.k8s

workload_kinds := {"Pod", "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob"}

# Workloads de larga duración: deben tener liveness/readiness (un Job o un Pod de una sola ejecución no).
long_running_kinds := {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet"}

is_workload if input.kind in workload_kinds

is_long_running if input.kind in long_running_kinds

pod_spec := input.spec if input.kind == "Pod"

pod_spec := input.spec.template.spec if input.kind in {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job"}

pod_spec := input.spec.jobTemplate.spec.template.spec if input.kind == "CronJob"

pod_labels := input.metadata.labels if input.kind == "Pod"

pod_labels := object.get(input.spec.template, ["metadata", "labels"], {}) if {
	input.kind in {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job"}
}

pod_labels := object.get(input.spec.jobTemplate.spec.template, ["metadata", "labels"], {}) if input.kind == "CronJob"

# Todos los contenedores (normales e init) con su tipo, para mensajes claros.
containers contains {"container": c, "type": "container"} if {
	some c in object.get(pod_spec, "containers", [])
}

containers contains {"container": c, "type": "initContainer"} if {
	some c in object.get(pod_spec, "initContainers", [])
}

# Un sidecar nativo (initContainer con restartPolicy Always) vive tanto como el pod.
main_containers contains c if {
	some c in object.get(pod_spec, "containers", [])
}

# Identificador legible del recurso en los mensajes: Kind/namespace/nombre.
resource_id := sprintf("%s/%s/%s", [input.kind, object.get(input.metadata, "namespace", "-"), input.metadata.name])

# Valor efectivo de un campo de seguridad: el del contenedor o, si no lo define, el del pod.
effective(c, field) := c.securityContext[field] if {
	object.get(c, ["securityContext", field], null) != null
} else := pod_spec.securityContext[field] if {
	object.get(pod_spec, ["securityContext", field], null) != null
}

# ¿La regla `rule` tiene una excepción VIGENTE para este recurso/contenedor? Una excepción vencida deja de
# aplicar y la regla vuelve a fallar (mismo criterio que exp: en .trivyignore).
excepted(rule, container_name) if {
	some e in data.exceptions
	e.rule == rule
	e.kind == input.kind
	e.name == input.metadata.name
	object.get(e, "container", "*") in {"*", container_name}
	time.parse_ns("2006-01-02", e.expires) > time.now_ns()
}
