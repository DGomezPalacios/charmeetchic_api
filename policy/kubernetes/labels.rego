# -----------------------------------------------------------------------------
# K8S-10: labels de trazabilidad (inventario de activos, ISO/IEC 27001:2022 A.5.9). Todo recurso debe decir
# QUÉ es y DE DÓNDE viene:
#   - Recursos propios (Kustomize): app.kubernetes.io/name (qué es) y app.kubernetes.io/part-of (a qué sistema
#     pertenece). En los workloads también en la plantilla del pod, que es lo que se ve en kubectl,
#     Prometheus y Loki.
#   - Recursos de charts de Helm de terceros: el esquema estándar de Helm, release
#     (app.kubernetes.io/instance o release) y chart con versión (helm.sh/chart o chart). Con eso se sabe
#     exactamente qué versión de qué chart y qué release de Helm creó el recurso, sin parchear charts ajenos.
# -----------------------------------------------------------------------------
package kubernetes

import data.lib.k8s

required_labels := ["app.kubernetes.io/name", "app.kubernetes.io/part-of"]

labels := object.get(input.metadata, "labels", {})

has_label(obj, key) if object.get(obj, key, "") != ""

helm_managed if labels["app.kubernetes.io/managed-by"] == "Helm"

helm_managed if has_label(labels, "helm.sh/chart")

helm_managed if has_label(labels, "chart")

helm_managed if labels.heritage == "Helm"

is_resource if {
	input.kind
	input.metadata.name
	not input.kind == "CustomResourceDefinition"
}

deny contains result("K8S-10", "ISO/IEC 27001:2022 A.5.9", sprintf("falta la label de trazabilidad '%s' en metadata.labels", [label])) if {
	is_resource
	not helm_managed
	some label in required_labels
	not has_label(labels, label)
	not k8s.excepted("K8S-10", "*")
}

deny contains result("K8S-10", "ISO/IEC 27001:2022 A.5.9", sprintf("falta la label de trazabilidad '%s' en la plantilla del pod", [label])) if {
	k8s.is_workload
	not helm_managed
	not input.kind == "Pod"
	some label in required_labels
	not has_label(k8s.pod_labels, label)
	not k8s.excepted("K8S-10", "*")
}

deny contains result("K8S-10", "ISO/IEC 27001:2022 A.5.9", "recurso de Helm sin release (app.kubernetes.io/instance o release)") if {
	is_resource
	helm_managed
	not has_label(labels, "app.kubernetes.io/instance")
	not has_label(labels, "release")
	not k8s.excepted("K8S-10", "*")
}

deny contains result("K8S-10", "ISO/IEC 27001:2022 A.5.9", "recurso de Helm sin chart y versión (helm.sh/chart o chart)") if {
	is_resource
	helm_managed
	not has_label(labels, "helm.sh/chart")
	not has_label(labels, "chart")
	not k8s.excepted("K8S-10", "*")
}
