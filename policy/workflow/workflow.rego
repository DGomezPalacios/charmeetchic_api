# -----------------------------------------------------------------------------
# Políticas sobre los workflows de GitHub Actions (.github/workflows/*.yml).
#   GHA-01: el workflow declara `permissions` a nivel raíz (mínimo privilegio del GITHUB_TOKEN).
#   GHA-02: las actions de terceros (fuera de actions/* y github/*) se fijan por el SHA completo del commit.
# -----------------------------------------------------------------------------
package workflow

result(rule, control, text) := {
	"msg": sprintf("[%s] %s (control: %s)", [rule, text, control]),
	"rule": rule,
	"control": control,
	"resource": "workflow",
}

is_workflow if input.jobs

deny contains result("GHA-01", "NIST SSDF SP 800-218 PO.5.1", "el workflow no define 'permissions' a nivel raíz: el GITHUB_TOKEN tendría los permisos por defecto del repositorio") if {
	is_workflow
	not "permissions" in object.keys(input)
}

deny contains result("GHA-01", "NIST SSDF SP 800-218 PO.5.1", sprintf("'permissions' a nivel raíz otorga escritura (%s): debe ser solo lectura y cada job pedir lo que necesita", [perm])) if {
	is_workflow
	is_object(input.permissions)
	some perm, level in input.permissions
	level == "write"
}

deny contains result("GHA-01", "NIST SSDF SP 800-218 PO.5.1", "'permissions' a nivel raíz es write-all") if {
	is_workflow
	input.permissions == "write-all"
}

first_party_owners := {"actions", "github"}

deny contains result("GHA-02", "NIST SSDF SP 800-218 PW.4.1", sprintf("el job '%s' usa la action de terceros '%s' sin fijarla por SHA de commit", [job_name, step.uses])) if {
	is_workflow
	some job_name, job in input.jobs
	some step in object.get(job, "steps", [])
	step.uses
	not startswith(step.uses, "./")
	not startswith(step.uses, "docker://")
	owner := split(step.uses, "/")[0]
	not owner in first_party_owners
	not regex.match(`@[0-9a-f]{40}$`, step.uses)
}
