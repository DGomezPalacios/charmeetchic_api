# -----------------------------------------------------------------------------
# Políticas sobre el repositorio. El input es un JSON que genera policy/scripts/repo-facts.sh:
#   {"trivyignore": [{"id", "exp", "justification", "line"}], "tracked_files": ["ruta", ...]}
#   REPO-01: cada riesgo aceptado en .trivyignore tiene vencimiento (exp:AAAA-MM-DD) y justificación.
#   REPO-02: no hay archivos de credenciales versionados (*.pem, *-admin-key*.json, .env).
# -----------------------------------------------------------------------------
package repo

result(rule, control, text) := {
	"msg": sprintf("[%s] %s (control: %s)", [rule, text, control]),
	"rule": rule,
	"control": control,
	"resource": "repositorio",
}

deny contains result("REPO-01", "NIST SSDF SP 800-218 RV.2.2", sprintf(".trivyignore línea %d: '%s' no tiene vencimiento exp:AAAA-MM-DD", [e.line, e.id])) if {
	some e in input.trivyignore
	not regex.match(`^\d{4}-\d{2}-\d{2}$`, object.get(e, "exp", ""))
}

deny contains result("REPO-01", "NIST SSDF SP 800-218 RV.2.2", sprintf(".trivyignore línea %d: '%s' no tiene justificación (bloque de comentarios # justo encima)", [e.line, e.id])) if {
	some e in input.trivyignore
	count(trim_space(object.get(e, "justification", ""))) < 20
}

# Un riesgo aceptado vencido no es un incumplimiento (Trivy vuelve a reportar la vulnerabilidad), pero la
# entrada quedó obsoleta: se avisa para limpiarla o renovarla con una nueva revisión.
warn contains result("REPO-01", "NIST SSDF SP 800-218 RV.2.2", sprintf(".trivyignore línea %d: la excepción de '%s' venció el %s; renovarla con nueva revisión o eliminarla", [e.line, e.id, e.exp])) if {
	some e in input.trivyignore
	regex.match(`^\d{4}-\d{2}-\d{2}$`, e.exp)
	time.parse_ns("2006-01-02", e.exp) < time.now_ns()
}

# REPO-03: cada excepción a las políticas de Kubernetes (policy/data/exceptions.yaml) está documentada como un
# riesgo aceptado: regla, recurso, control, justificación y vencimiento, y no excede 1 año.
exception_fields := ["id", "rule", "kind", "name", "control", "justification", "expires"]

deny contains result("REPO-03", "ISO/IEC 27001:2022 A.5.36", sprintf("la excepción %s no define '%s'", [object.get(e, "id", "?"), field])) if {
	some e in data.exceptions
	some field in exception_fields
	count(trim_space(sprintf("%v", [object.get(e, field, "")]))) == 0
}

deny contains result("REPO-03", "ISO/IEC 27001:2022 A.5.36", sprintf("la excepción %s tiene una justificación demasiado corta", [object.get(e, "id", "?")])) if {
	some e in data.exceptions
	count(trim_space(object.get(e, "justification", ""))) < 40
}

deny contains result("REPO-03", "ISO/IEC 27001:2022 A.5.36", sprintf("la excepción %s vence a más de 1 año (%s): debe revisarse al menos una vez al año", [e.id, e.expires])) if {
	some e in data.exceptions
	time.parse_ns("2006-01-02", e.expires) > time.add_date(time.now_ns(), 1, 0, 0)
}

warn contains result("REPO-03", "ISO/IEC 27001:2022 A.5.36", sprintf("la excepción %s venció el %s: la regla %s vuelve a aplicarse a %s/%s", [e.id, e.expires, e.rule, e.kind, e.name])) if {
	some e in data.exceptions
	time.parse_ns("2006-01-02", e.expires) < time.now_ns()
}

credential_patterns := [
	`(^|/)[^/]*\.pem$`,
	`(^|/)[^/]*-admin-key[^/]*\.json$`,
	`(^|/)\.env$`,
	`(^|/)\.env\.(local|prod|production|dev|development|staging)$`,
]

deny contains result("REPO-02", "OWASP ASVS 4.0.3 V2.10.4", sprintf("archivo de credenciales versionado: '%s' (sácalo con git rm --cached y rota la credencial)", [f])) if {
	some f in input.tracked_files
	some p in credential_patterns
	regex.match(p, f)
}
