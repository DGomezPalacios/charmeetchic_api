#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Auditoría de cumplimiento automatizada (job compliance-audit del CI; también se corre en local).
#
#   1. Renderiza los manifiestos: kustomize build de los overlays dev, prod y aks y de k8s/observability, el pod
#      del smoke test (k8s/ci) y helm template de los charts del stack de monitoreo (versiones fijas en
#      k8s/observability/versions.env, con nuestros values).
#   2. Genera el input del repositorio (policy/scripts/repo-facts.sh).
#   3. Evalúa las políticas OPA/Rego de policy/ con conftest: kubernetes (manifiestos), workflow (GitHub
#      Actions) y repo (.trivyignore, credenciales versionadas, excepciones).
#   4. Escribe el reporte: <salida>/compliance-report.json (artefacto) y <salida>/compliance-report.md (Job
#      Summary).
#   5. Termina con código 1 si hay CUALQUIER incumplimiento (deny). Las advertencias (warn) no bloquean.
#
# Requiere: conftest, kustomize, helm, jq, git.
#   policy/scripts/compliance-audit.sh [directorio-de-salida]      (por defecto: compliance-report)
#   SKIP_CHARTS=1 policy/scripts/compliance-audit.sh               (sin red: omite los charts de Helm)
# -----------------------------------------------------------------------------
set -euo pipefail

OUT="${1:-compliance-report}"
RENDER="$OUT/rendered"
RESULTS="$OUT/results"
rm -rf "$OUT"
mkdir -p "$RENDER" "$RESULTS"

# shellcheck source=/dev/null
. k8s/observability/versions.env

echo "==> Renderizando manifiestos"
for overlay in dev prod aks; do
  kustomize build "k8s/overlays/${overlay}" > "$RENDER/overlay-${overlay}.yaml"
done
kustomize build k8s/observability > "$RENDER/observability.yaml"
cp k8s/ci/smoke-test-pod.yaml "$RENDER/ci-smoke-test-pod.yaml"

if [ "${SKIP_CHARTS:-0}" != "1" ]; then
  PROM_REPO=https://prometheus-community.github.io/helm-charts
  GRAFANA_REPO=https://grafana.github.io/helm-charts
  helm template kps kube-prometheus-stack --repo "$PROM_REPO" --version "$KPS_CHART_VERSION" \
    -n monitoring -f k8s/observability/kube-prometheus-stack-values.yaml > "$RENDER/chart-kube-prometheus-stack.yaml"
  helm template loki loki --repo "$GRAFANA_REPO" --version "$LOKI_CHART_VERSION" \
    -n monitoring -f k8s/observability/loki-values.yaml > "$RENDER/chart-loki.yaml"
  helm template alloy alloy --repo "$GRAFANA_REPO" --version "$ALLOY_CHART_VERSION" \
    -n monitoring -f k8s/observability/alloy-values.yaml > "$RENDER/chart-alloy.yaml"
  helm template pushgateway prometheus-pushgateway --repo "$PROM_REPO" --version "$PUSHGATEWAY_CHART_VERSION" \
    -n monitoring -f k8s/observability/pushgateway-values.yaml > "$RENDER/chart-pushgateway.yaml"
else
  echo "    SKIP_CHARTS=1: se omiten los charts de Helm"
fi

echo "==> Generando el input del repositorio"
policy/scripts/repo-facts.sh > "$RENDER/repo-facts.json"

# conftest test por objetivo; --no-fail: el código de salida lo decide este script con el total de deny.
run_conftest() {
  local name="$1" namespace="$2"; shift 2
  conftest test "$@" --policy policy --data policy/data --namespace "$namespace" \
    --output json --no-fail --no-color > "$RESULTS/${name}.json"
  jq --arg target "$name" --arg ns "$namespace" '[.[] | . + {target: $target, policy_namespace: $ns}]' \
    "$RESULTS/${name}.json" > "$RESULTS/${name}.tmp" && mv "$RESULTS/${name}.tmp" "$RESULTS/${name}.json"
}

echo "==> Evaluando políticas (conftest $(conftest --version | head -1 | awk '{print $2}'))"
for f in "$RENDER"/overlay-*.yaml "$RENDER"/observability.yaml "$RENDER"/ci-*.yaml "$RENDER"/chart-*.yaml; do
  [ -f "$f" ] || continue
  run_conftest "$(basename "$f" .yaml)" kubernetes "$f"
done
for wf in .github/workflows/*.yml; do
  run_conftest "workflow-$(basename "$wf" .yml)" workflow "$wf"
done
run_conftest repositorio repo "$RENDER/repo-facts.json"

# Reporte JSON consolidado (artefacto): resultados por objetivo, incumplimientos, advertencias, excepciones
# vigentes y el catálogo de controles evaluados.
conftest parse policy/data/exceptions.yaml > "$RESULTS/exceptions.parsed.json"
# Todos los resultados de conftest, sin el JSON de excepciones (que entra aparte con --slurpfile).
mapfile -t result_files < <(ls "$RESULTS"/*.json | grep -v "exceptions.parsed.json")
jq -s \
  --arg commit "${GITHUB_SHA:-$(git rev-parse HEAD)}" \
  --arg ref "${GITHUB_REF:-$(git rev-parse --abbrev-ref HEAD)}" \
  --arg date "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg conftest "$(conftest --version | head -1 | awk '{print $2}')" \
  --slurpfile catalog policy/controles.json \
  --slurpfile exc "$RESULTS/exceptions.parsed.json" '
  (map(.[])) as $all
  | {
      generated_at: $date, commit: $commit, ref: $ref, conftest_version: $conftest,
      targets: ($all | group_by(.target) | map({
        target: .[0].target,
        policy_namespace: .[0].policy_namespace,
        documents: length,
        checks: (map(.successes + ((.failures // []) | length) + ((.warnings // []) | length)) | add),
        passed: (map(.successes) | add),
        failures: (map((.failures // []) | length) | add),
        warnings: (map((.warnings // []) | length) | add)
      })),
      failures: [$all[] | .target as $t | (.failures // [])[] | {target: $t, rule: .metadata.rule, control: .metadata.control, resource: .metadata.resource, msg}],
      warnings: [$all[] | .target as $t | (.warnings // [])[] | {target: $t, rule: .metadata.rule, control: .metadata.control, resource: .metadata.resource, msg}],
      exceptions: ($exc[0].exceptions // []),
      catalog: $catalog[0]
    }
  | .summary = {
      checks: ([.targets[].checks] | add), failures: (.failures | length), warnings: (.warnings | length),
      exceptions: (.exceptions | length), result: (if (.failures | length) == 0 then "CUMPLE" else "NO CUMPLE" end)
    }' "${result_files[@]}" > "$OUT/compliance-report.json"

# Reporte Markdown (Job Summary). Los "|" de los mensajes se escapan para no romper las tablas.
jq -r '
  def esc: tostring | gsub("\\|"; "\\|") | gsub("\n"; " ");
  "## " + (if .summary.failures == 0 then "✅" else "❌" end) + " Auditoría de cumplimiento: " + .summary.result,
  "",
  "Commit `" + .commit + "` (" + .ref + ") · conftest " + .conftest_version + " · " + .generated_at,
  "",
  "| Comprobaciones | Incumplimientos | Advertencias | Excepciones vigentes |",
  "|---:|---:|---:|---:|",
  "| \(.summary.checks) | \(.summary.failures) | \(.summary.warnings) | \(.summary.exceptions) |",
  "",
  "### Resultado por objetivo",
  "",
  "| Objetivo | Políticas | Archivos | Comprobaciones | Cumplen | Incumplen | Advertencias |",
  "|---|---|---:|---:|---:|---:|---:|",
  (.targets[] | "| `\(.target)` | \(.policy_namespace) | \(.documents) | \(.checks) | \(.passed) | \(.failures) | \(.warnings) |"),
  "",
  (if (.failures | length) > 0 then
    "### ❌ Incumplimientos (bloquean el pipeline)", "",
    "| Regla | Control normativo | Objetivo | Recurso | Detalle |", "|---|---|---|---|---|",
    (.failures[] | "| \(.rule) | \(.control | esc) | `\(.target)` | `\(.resource | esc)` | \(.msg | esc) |"), ""
  else "No hay incumplimientos.", "" end),
  (if (.warnings | length) > 0 then
    "### ⚠️ Advertencias (no bloquean)", "",
    "| Regla | Control normativo | Detalle |", "|---|---|---|",
    (.warnings[] | "| \(.rule) | \(.control | esc) | \(.msg | esc) |"), ""
  else empty end),
  "### Excepciones vigentes (policy/data/exceptions.yaml)",
  "",
  "| Id | Regla | Recurso | Control | Vence | Justificación |",
  "|---|---|---|---|---|---|",
  (.exceptions[] | "| \(.id) | \(.rule) | `\(.kind)/\(.name)\(if .container then "/" + .container else "" end)` | \(.control | esc) | \(.expires) | \(.justification | esc) |"),
  "",
  "<details><summary>Catálogo de controles evaluados</summary>",
  "",
  "| Regla | Qué verifica | Control normativo | Objetivo | Qué bloquea |",
  "|---|---|---|---|---|",
  (.catalog[] | "| \(.regla) | \(.descripcion | esc) | \(.control | esc) | \(.objetivo | esc) | \(.bloquea | esc) |"),
  "",
  "</details>"
' "$OUT/compliance-report.json" > "$OUT/compliance-report.md"

FAILURES=$(jq '.summary.failures' "$OUT/compliance-report.json")
WARNINGS=$(jq '.summary.warnings' "$OUT/compliance-report.json")
CHECKS=$(jq '.summary.checks' "$OUT/compliance-report.json")
echo "==> Resultado: ${CHECKS} comprobaciones, ${FAILURES} incumplimientos, ${WARNINGS} advertencias"
if [ "$FAILURES" -gt 0 ]; then
  jq -r '.failures[] | "  ✗ " + .msg' "$OUT/compliance-report.json"
  exit 1
fi
