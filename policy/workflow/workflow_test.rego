# Tests de las políticas del workflow (GHA-01 y GHA-02).
package workflow_test

import data.workflow

rules_of(results) := {r.rule | some r in results}

good := {
	"name": "CI",
	"permissions": {"contents": "read"},
	"jobs": {"build": {"runs-on": "ubuntu-latest", "steps": [
		{"uses": "actions/checkout@v4"},
		{"uses": "docker/setup-buildx-action@f87e5991a6d7451dcb8d9637bfbc97413f497069"},
		{"run": "mvn -B verify"},
	]}},
}

test_compliant_workflow_passes if {
	count(workflow.deny) == 0 with input as good
}

test_missing_permissions_denied if {
	"GHA-01" in rules_of(workflow.deny) with input as json.remove(good, ["permissions"])
}

test_write_permissions_at_root_denied if {
	"GHA-01" in rules_of(workflow.deny) with input as object.union(good, {"permissions": {"contents": "write"}})
}

test_write_all_denied if {
	"GHA-01" in rules_of(workflow.deny) with input as object.union(good, {"permissions": "write-all"})
}

test_third_party_action_by_tag_denied if {
	wf := json.patch(good, [{"op": "add", "path": "/jobs/build/steps/-", "value": {"uses": "aquasecurity/trivy-action@v0.36.0"}}])
	"GHA-02" in rules_of(workflow.deny) with input as wf
}

test_non_workflow_yaml_ignored if {
	count(workflow.deny) == 0 with input as {"kind": "ConfigMap"}
}
