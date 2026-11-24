# Tests de las políticas de workloads (conftest verify). Cada regla tiene un caso que pasa y uno que falla.
package kubernetes_test

import data.kubernetes

# Deployment que cumple TODAS las reglas (base para los casos negativos).
good_container := {
	"name": "api",
	"image": "ghcr.io/dgomezpalacios/charmeetchic-api:sha-abc1234",
	"resources": {"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "1", "memory": "256Mi"}},
	"livenessProbe": {"httpGet": {"path": "/api/health", "port": "http"}},
	"readinessProbe": {"httpGet": {"path": "/api/health", "port": "http"}},
	"securityContext": {
		"allowPrivilegeEscalation": false,
		"readOnlyRootFilesystem": true,
		"capabilities": {"drop": ["ALL"]},
	},
}

deployment(container) := {
	"apiVersion": "apps/v1",
	"kind": "Deployment",
	"metadata": {
		"name": "charmeetchic-api",
		"namespace": "charmeetchic",
		"labels": {"app.kubernetes.io/name": "charmeetchic-api", "app.kubernetes.io/part-of": "charmeetchic"},
	},
	"spec": {"template": {
		"metadata": {"labels": {"app.kubernetes.io/name": "charmeetchic-api", "app.kubernetes.io/part-of": "charmeetchic"}},
		"spec": {
			"securityContext": {"runAsNonRoot": true, "runAsUser": 1001},
			"containers": [container],
		},
	}},
}

rules_of(results) := {r.rule | some r in results}

test_compliant_deployment_passes if {
	count(kubernetes.deny) == 0 with input as deployment(good_container) with data.exceptions as []
}

test_root_container_denied if {
	input_doc := json.patch(deployment(good_container), [{"op": "replace", "path": "/spec/template/spec/securityContext", "value": {}}])
	"K8S-01" in rules_of(kubernetes.deny) with input as input_doc with data.exceptions as []
}

test_run_as_user_zero_denied if {
	c := object.union(good_container, {"securityContext": object.union(good_container.securityContext, {"runAsUser": 0})})
	"K8S-01" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_privileged_denied if {
	c := object.union(good_container, {"securityContext": object.union(good_container.securityContext, {"privileged": true})})
	"K8S-02" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_privilege_escalation_denied if {
	c := json.remove(good_container, ["securityContext/allowPrivilegeEscalation"])
	"K8S-03" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_writable_root_fs_denied if {
	c := object.union(good_container, {"securityContext": object.union(good_container.securityContext, {"readOnlyRootFilesystem": false})})
	"K8S-04" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_capabilities_not_dropped_denied if {
	c := json.patch(good_container, [{"op": "replace", "path": "/securityContext/capabilities", "value": {"add": ["NET_ADMIN"]}}])
	"K8S-05" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_missing_limits_denied if {
	c := json.remove(good_container, ["resources/limits"])
	results := kubernetes.deny with input as deployment(c) with data.exceptions as []
	"K8S-06" in rules_of(results)
	count([r | some r in results; r.rule == "K8S-06"]) == 2
}

test_missing_probes_denied if {
	c := json.remove(good_container, ["livenessProbe", "readinessProbe"])
	results := kubernetes.deny with input as deployment(c) with data.exceptions as []
	count([r | some r in results; r.rule == "K8S-07"]) == 2
}

test_job_without_probes_allowed if {
	c := json.remove(good_container, ["livenessProbe", "readinessProbe"])
	job := json.patch(deployment(c), [{"op": "replace", "path": "/kind", "value": "Job"}])
	not "K8S-07" in rules_of(kubernetes.deny) with input as job with data.exceptions as []
}

test_latest_tag_denied if {
	c := object.union(good_container, {"image": "nginx:latest"})
	"K8S-08" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_untagged_image_denied if {
	c := object.union(good_container, {"image": "localhost:5000/nginx"})
	"K8S-08" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_digest_image_allowed if {
	c := object.union(good_container, {"image": "postgres@sha256:f9cf3e1f0066f0eb0c48707d864f0ac5941e9ff454a072077d97a4d278d07d73"})
	not "K8S-08" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as []
}

test_host_network_denied if {
	d := json.patch(deployment(good_container), [{"op": "add", "path": "/spec/template/spec/hostNetwork", "value": true}])
	"K8S-09" in rules_of(kubernetes.deny) with input as d with data.exceptions as []
}

test_init_container_checked if {
	bad_init := {"name": "init", "image": "busybox:1.37", "securityContext": {"privileged": true}}
	d := json.patch(deployment(good_container), [{"op": "add", "path": "/spec/template/spec/initContainers", "value": [bad_init]}])
	results := kubernetes.deny with input as d with data.exceptions as []
	"K8S-02" in rules_of(results)
	"K8S-06" in rules_of(results)
}

test_messages_cite_normative_control if {
	c := object.union(good_container, {"securityContext": {"privileged": true}})
	results := kubernetes.deny with input as deployment(c) with data.exceptions as []
	count(results) > 0
	every r in results {
		regex.match(`\(control: (CIS Kubernetes Benchmark|NIST SSDF SP 800-218|OWASP ASVS|ISO/IEC 27001:2022)`, r.msg)
	}
}

# Excepciones: una vigente silencia SOLO esa regla en ese recurso; una vencida no silencia nada.
test_active_exception_applies if {
	d := json.patch(deployment(good_container), [{"op": "add", "path": "/spec/template/spec/hostNetwork", "value": true}])
	exc := [{"id": "EXC-T", "rule": "K8S-09", "kind": "Deployment", "name": "charmeetchic-api", "expires": "2099-01-01"}]
	count(kubernetes.deny) == 0 with input as d with data.exceptions as exc
}

test_expired_exception_does_not_apply if {
	d := json.patch(deployment(good_container), [{"op": "add", "path": "/spec/template/spec/hostNetwork", "value": true}])
	exc := [{"id": "EXC-T", "rule": "K8S-09", "kind": "Deployment", "name": "charmeetchic-api", "expires": "2020-01-01"}]
	"K8S-09" in rules_of(kubernetes.deny) with input as d with data.exceptions as exc
}

test_exception_for_other_container_does_not_apply if {
	c := json.remove(good_container, ["livenessProbe"])
	exc := [{"id": "EXC-T", "rule": "K8S-07", "kind": "Deployment", "name": "charmeetchic-api", "container": "otro", "expires": "2099-01-01"}]
	"K8S-07" in rules_of(kubernetes.deny) with input as deployment(c) with data.exceptions as exc
}
