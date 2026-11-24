#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Publica en el Pushgateway del clúster las métricas del despliegue (job deploy-aks del CI; también sirve en
# local contra kind). El Pushgateway NO está expuesto a internet: este script abre un `kubectl port-forward`
# al Service ClusterIP, empuja las métricas por localhost y cierra el túnel.
#
# Variables de entorno (todas obligatorias salvo PUSHGATEWAY_LOCAL_PORT):
#   ENVIRONMENT        entorno desplegado (production, staging...)  -> label "environment"
#   DEPLOY_DURATION    duración del despliegue en segundos
#   DEPLOY_TIMESTAMP   fin del despliegue, epoch en segundos
#   COVERAGE_LINES     cobertura de líneas (%)      COVERAGE_BRANCHES  cobertura de ramas (%)
#   TESTS_TOTAL        total de tests ejecutados
#   COMMIT             SHA del commit desplegado    IMAGE  imagen desplegada    RUN_ID  id de la ejecución del CI
#
# Agrupación: /metrics/job/charmeetchic-pipeline/environment/<entorno>. Se usa PUT, que REEMPLAZA el grupo
# completo: la serie charmeetchic_deploy_info del commit anterior desaparece y solo queda la del actual.
# -----------------------------------------------------------------------------
set -euo pipefail

: "${ENVIRONMENT:?}" "${DEPLOY_DURATION:?}" "${DEPLOY_TIMESTAMP:?}" "${COVERAGE_LINES:?}" "${COVERAGE_BRANCHES:?}"
: "${TESTS_TOTAL:?}" "${COMMIT:?}" "${IMAGE:?}" "${RUN_ID:?}"
PORT="${PUSHGATEWAY_LOCAL_PORT:-19091}"
URL="http://127.0.0.1:${PORT}/metrics/job/charmeetchic-pipeline/environment/${ENVIRONMENT}"

kubectl -n monitoring port-forward svc/pushgateway "${PORT}:9091" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

# Espera a que el túnel responda (máx. 30 s).
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/-/ready" >/dev/null 2>&1; then break; fi
  sleep 1
done

# Formato de exposición de Prometheus (texto). Una línea HELP/TYPE por métrica.
cat <<EOF | curl -fsS --retry 3 --retry-all-errors -X PUT --data-binary @- "$URL"
# HELP charmeetchic_deploy_duration_seconds Duración del último despliegue (apply -> rollout -> smoke test).
# TYPE charmeetchic_deploy_duration_seconds gauge
charmeetchic_deploy_duration_seconds ${DEPLOY_DURATION}
# HELP charmeetchic_deploy_timestamp_seconds Momento (epoch) en que terminó el último despliegue.
# TYPE charmeetchic_deploy_timestamp_seconds gauge
charmeetchic_deploy_timestamp_seconds ${DEPLOY_TIMESTAMP}
# HELP charmeetchic_coverage_lines_percent Cobertura de líneas (JaCoCo) del commit desplegado.
# TYPE charmeetchic_coverage_lines_percent gauge
charmeetchic_coverage_lines_percent ${COVERAGE_LINES}
# HELP charmeetchic_coverage_branches_percent Cobertura de ramas (JaCoCo) del commit desplegado.
# TYPE charmeetchic_coverage_branches_percent gauge
charmeetchic_coverage_branches_percent ${COVERAGE_BRANCHES}
# HELP charmeetchic_tests_total Tests ejecutados (unitarios + integración) del commit desplegado.
# TYPE charmeetchic_tests_total gauge
charmeetchic_tests_total ${TESTS_TOTAL}
# HELP charmeetchic_deploy_info Commit, imagen y ejecución del CI desplegados (valor siempre 1).
# TYPE charmeetchic_deploy_info gauge
charmeetchic_deploy_info{commit="${COMMIT}",image="${IMAGE}",run_id="${RUN_ID}"} 1
EOF

echo "Métricas publicadas en el Pushgateway (job=charmeetchic-pipeline, environment=${ENVIRONMENT}):"
curl -fsS "http://127.0.0.1:${PORT}/metrics" | grep -E '^charmeetchic_' || true
