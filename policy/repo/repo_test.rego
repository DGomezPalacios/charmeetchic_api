# Tests de las políticas del repositorio (REPO-01, REPO-02 y REPO-03).
package repo_test

import data.repo

rules_of(results) := {r.rule | some r in results}

just := "Riesgo aceptado: la API es REST y no usa la clase vulnerable; se corrige al migrar a Boot 4."

good := {
	"trivyignore": [{"id": "CVE-2026-1", "exp": "2099-12-31", "line": 5, "justification": just}],
	"tracked_files": ["pom.xml", ".env.example", "src/main/resources/application.yml"],
}

good_exc := [{
	"id": "EXC-01", "rule": "K8S-09", "kind": "DaemonSet", "name": "node-exporter",
	"control": "CIS 5.2.4", "justification": "node-exporter necesita hostNetwork para medir la red del nodo.",
	"expires": "2026-12-31",
}]

test_compliant_repo_passes if {
	count(repo.deny) == 0 with input as good with data.exceptions as []
}

test_trivyignore_without_exp_denied if {
	r := object.union(good, {"trivyignore": [{"id": "CVE-2026-1", "exp": "", "line": 5, "justification": just}]})
	"REPO-01" in rules_of(repo.deny) with input as r with data.exceptions as []
}

test_trivyignore_without_justification_denied if {
	r := object.union(good, {"trivyignore": [{"id": "CVE-2026-1", "exp": "2099-12-31", "line": 5, "justification": ""}]})
	"REPO-01" in rules_of(repo.deny) with input as r with data.exceptions as []
}

test_expired_trivyignore_only_warns if {
	r := object.union(good, {"trivyignore": [{"id": "CVE-2026-1", "exp": "2020-01-01", "line": 5, "justification": just}]})
	count(repo.deny) == 0 with input as r with data.exceptions as []
	"REPO-01" in rules_of(repo.warn) with input as r with data.exceptions as []
}

test_pem_file_denied if {
	r := object.union(good, {"tracked_files": ["certs/server.pem"]})
	"REPO-02" in rules_of(repo.deny) with input as r with data.exceptions as []
}

test_firebase_admin_key_denied if {
	r := object.union(good, {"tracked_files": ["charme-et-chic-firebase-admin-key.json"]})
	"REPO-02" in rules_of(repo.deny) with input as r with data.exceptions as []
}

test_dotenv_denied_but_example_allowed if {
	"REPO-02" in rules_of(repo.deny) with input as object.union(good, {"tracked_files": ["deploy/.env"]}) with data.exceptions as []
	count(repo.deny) == 0 with input as object.union(good, {"tracked_files": [".env.example"]}) with data.exceptions as []
}

test_valid_exception_passes if {
	count(repo.deny) == 0 with input as good with data.exceptions as good_exc
}

test_exception_without_justification_denied if {
	exc := [object.union(good_exc[0], {"justification": ""})]
	"REPO-03" in rules_of(repo.deny) with input as good with data.exceptions as exc
}

test_exception_longer_than_one_year_denied if {
	exc := [object.union(good_exc[0], {"expires": "2099-01-01"})]
	"REPO-03" in rules_of(repo.deny) with input as good with data.exceptions as exc
}
