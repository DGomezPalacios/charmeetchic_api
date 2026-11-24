# Tests de K8S-10 (labels de trazabilidad).
package kubernetes_test

import data.kubernetes

test_service_with_labels_passes if {
	svc := {"apiVersion": "v1", "kind": "Service", "metadata": {"name": "api", "labels": {"app.kubernetes.io/name": "api", "app.kubernetes.io/part-of": "charmeetchic"}}}
	count(kubernetes.deny) == 0 with input as svc with data.exceptions as []
}

test_configmap_without_labels_denied if {
	cm := {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "cfg"}}
	results := kubernetes.deny with input as cm with data.exceptions as []
	count([r | some r in results; r.rule == "K8S-10"]) == 2
}

test_pod_template_without_labels_denied if {
	d := json.patch(deployment(good_container), [{"op": "replace", "path": "/spec/template/metadata/labels", "value": {}}])
	results := kubernetes.deny with input as d with data.exceptions as []
	some r in results
	r.rule == "K8S-10"
	contains(r.msg, "plantilla del pod")
}

test_helm_resource_with_chart_and_release_passes if {
	cm := {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "kps-grafana", "labels": {"helm.sh/chart": "grafana-14.5.0", "app.kubernetes.io/instance": "kps"}}}
	count(kubernetes.deny) == 0 with input as cm with data.exceptions as []
}

test_helm_resource_without_release_denied if {
	cm := {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "x", "labels": {"app.kubernetes.io/managed-by": "Helm", "chart": "x-1.0.0"}}}
	"K8S-10" in rules_of(kubernetes.deny) with input as cm with data.exceptions as []
}

test_crd_ignored if {
	crd := {"apiVersion": "apiextensions.k8s.io/v1", "kind": "CustomResourceDefinition", "metadata": {"name": "x.example.com"}}
	count(kubernetes.deny) == 0 with input as crd with data.exceptions as []
}
