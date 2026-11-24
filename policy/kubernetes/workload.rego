# -----------------------------------------------------------------------------
# Políticas de seguridad y operación de los workloads de Kubernetes (Deployment, StatefulSet, DaemonSet, Job,
# CronJob, Pod). Se evalúan con conftest sobre los manifiestos RENDERIZADOS (kustomize build de cada overlay y
# de k8s/observability, y helm template del stack de monitoreo) en el job compliance-audit del CI.
#
# Cada regla devuelve un objeto con:
#   msg     -> mensaje legible, que SIEMPRE nombra el control normativo que cubre;
#   rule    -> identificador (K8S-xx), el mismo de policy/controles.json y de docs/observabilidad.md;
#   control -> control normativo (CIS Kubernetes Benchmark, NIST SSDF SP 800-218, OWASP ASVS, ISO/IEC 27001:2022).
# Cualquier "deny" hace fallar el job. Las excepciones viven en policy/data/exceptions.yaml (con justificación y
# vencimiento) y se reportan aparte.
# -----------------------------------------------------------------------------
package kubernetes

import data.lib.k8s

result(rule, control, text) := {
	"msg": sprintf("[%s] %s: %s (control: %s)", [rule, k8s.resource_id, text, control]),
	"rule": rule,
	"control": control,
	"resource": k8s.resource_id,
}

# K8S-01: los contenedores no corren como root.
deny contains result("K8S-01", "CIS Kubernetes Benchmark 5.2.6", sprintf("el %s '%s' puede correr como root: define runAsNonRoot: true (contenedor o pod)", [x.type, x.container.name])) if {
	k8s.is_workload
	some x in k8s.containers
	not runs_as_non_root(x.container)
	not k8s.excepted("K8S-01", x.container.name)
}

deny contains result("K8S-01", "CIS Kubernetes Benchmark 5.2.6", sprintf("el %s '%s' define runAsUser: 0 (root)", [x.type, x.container.name])) if {
	k8s.is_workload
	some x in k8s.containers
	k8s.effective(x.container, "runAsUser") == 0
	not k8s.excepted("K8S-01", x.container.name)
}

# K8S-02: sin contenedores privilegiados.
deny contains result("K8S-02", "CIS Kubernetes Benchmark 5.2.2", sprintf("el %s '%s' es privileged: true", [x.type, x.container.name])) if {
	k8s.is_workload
	some x in k8s.containers
	x.container.securityContext.privileged == true
	not k8s.excepted("K8S-02", x.container.name)
}

# K8S-03: sin escalada de privilegios (debe estar explícitamente en false).
deny contains result("K8S-03", "CIS Kubernetes Benchmark 5.2.5", sprintf("el %s '%s' no define allowPrivilegeEscalation: false", [x.type, x.container.name])) if {
	k8s.is_workload
	some x in k8s.containers
	not object.get(x.container, ["securityContext", "allowPrivilegeEscalation"], true) == false
	not k8s.excepted("K8S-03", x.container.name)
}

# K8S-04: sistema de archivos raíz de solo lectura.
deny contains result("K8S-04", "CIS Kubernetes Benchmark 5.7.3", sprintf("el %s '%s' no define readOnlyRootFilesystem: true", [x.type, x.container.name])) if {
	k8s.is_workload
	some x in k8s.containers
	not object.get(x.container, ["securityContext", "readOnlyRootFilesystem"], false) == true
	not k8s.excepted("K8S-04", x.container.name)
}

# K8S-05: se eliminan todas las capabilities de Linux.
deny contains result("K8S-05", "CIS Kubernetes Benchmark 5.2.9", sprintf("el %s '%s' no elimina todas las capabilities (capabilities.drop: [\"ALL\"])", [x.type, x.container.name])) if {
	k8s.is_workload
	some x in k8s.containers
	not "ALL" in object.get(x.container, ["securityContext", "capabilities", "drop"], [])
	not k8s.excepted("K8S-05", x.container.name)
}

# K8S-06: requests y limits de CPU y memoria definidos (capacidad planificada, sin vecinos ruidosos).
deny contains result("K8S-06", "ISO/IEC 27001:2022 A.8.6", sprintf("el %s '%s' no define resources.%s.%s", [x.type, x.container.name, kind, res])) if {
	k8s.is_workload
	some x in k8s.containers
	some kind in ["requests", "limits"]
	some res in ["cpu", "memory"]
	object.get(x.container, ["resources", kind, res], null) == null
	not k8s.excepted("K8S-06", x.container.name)
}

# K8S-07: liveness y readiness probes en los workloads de larga duración (contenedores principales).
deny contains result("K8S-07", "ISO/IEC 27001:2022 A.8.16", sprintf("el contenedor '%s' no define %s", [c.name, probe])) if {
	k8s.is_long_running
	some c in k8s.main_containers
	some probe in ["livenessProbe", "readinessProbe"]
	not c[probe]
	not k8s.excepted("K8S-07", c.name)
}

# K8S-08: imágenes con versión explícita, nunca "latest" ni sin tag (reproducibilidad y procedencia).
deny contains result("K8S-08", "NIST SSDF SP 800-218 PW.4.1", sprintf("el %s '%s' usa la imagen '%s' sin tag fijo (latest o sin tag)", [x.type, x.container.name, x.container.image])) if {
	k8s.is_workload
	some x in k8s.containers
	not image_pinned(x.container.image)
	not k8s.excepted("K8S-08", x.container.name)
}

# K8S-09: el pod no comparte los namespaces del nodo (red, procesos, IPC).
deny contains result("K8S-09", "CIS Kubernetes Benchmark 5.2.3-5.2.4", sprintf("el pod usa %s: true", [field])) if {
	k8s.is_workload
	some field in ["hostNetwork", "hostPID", "hostIPC"]
	k8s.pod_spec[field] == true
	not k8s.excepted("K8S-09", "*")
}

# Una imagen está fijada si tiene digest, o un tag distinto de latest. El tag es lo que va después del último
# ":" del último segmento (un ":" antes de un "/" es el puerto del registro).
image_pinned(image) if contains(image, "@sha256:")

image_pinned(image) if {
	not contains(image, "@")
	parts := split(image, "/")
	last := parts[count(parts) - 1]
	contains(last, ":")
	tag := split(last, ":")[1]
	tag != "latest"
	tag != ""
}

# runAsNonRoot efectivo (contenedor o pod). Regla auxiliar para negarla con `not`: si la función no está
# definida (ningún nivel lo declara), la regla es falsa y el contenedor cuenta como "puede ser root".
runs_as_non_root(c) if k8s.effective(c, "runAsNonRoot") == true
