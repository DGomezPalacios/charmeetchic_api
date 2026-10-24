#!/usr/bin/env bash
# =============================================================================
# Levanta todo el sistema en GitHub Codespaces (o en un PC con Docker):
#   1. genera .env con contraseñas aleatorias (si no existe; no muestra contraseñas)
#   2. construye y levanta docker-compose.prod.yml + docker-compose.codespaces.yml
#   3. espera a que todo esté sano, prueba los endpoints y muestra la URL pública del gateway
#
#   ./deploy/codespaces-up.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose.prod.yml -f docker-compose.codespaces.yml"
FRONT="https://charme-et-chic.web.app,https://charme-et-chic.firebaseapp.com"

if [ -n "${CODESPACE_NAME:-}" ] && [ -n "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}" ]; then
  HOST="${CODESPACE_NAME}-8000.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
  CONSOLE_URL="https://${CODESPACE_NAME}-15672.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
  PUBLIC_URL="https://${HOST}"
else
  echo "(No estás en un Codespace: se usará http://localhost:8000)"
  HOST="localhost"
  CONSOLE_URL="http://localhost:15672"
  PUBLIC_URL="http://localhost:8000"
fi

echo ">> [1/4] .env"
if [ -f .env ]; then
  echo "   .env ya existe: se reutiliza (contraseñas sin cambios)"
else
  ./deploy/generar-env.sh "$HOST" "$FRONT" >/dev/null
  echo "   .env creado con contraseñas aleatorias (permisos 600)"
fi
grep -E '^(DOMAIN|FRONTEND_ORIGIN|RABBITMQ_USERNAME|MESSAGING_SIMULATED_FAILURES)=' .env | sed 's/^/   /'

echo ">> [2/4] Construyendo imágenes (la primera vez tarda varios minutos)"
for s in backend rabbit-admin gateway; do
  start=$(date +%s)
  $COMPOSE build "$s" > "/tmp/build-$s.log" 2>&1 || { echo "   ERROR construyendo $s:"; tail -30 "/tmp/build-$s.log"; exit 1; }
  echo "   $s OK ($(( $(date +%s) - start )) s)"
done

echo ">> [3/4] Levantando el stack"
$COMPOSE up -d 2>&1 | tail -2

echo "   Esperando a que el gateway, el backend y rabbit-admin estén sanos (máx. 5 min)..."
deadline=$(( $(date +%s) + 300 ))
for s in backend rabbit-admin gateway; do
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$($COMPOSE ps -q "$s")" 2>/dev/null)" = "healthy" ]; do
    if [ "$(date +%s)" -gt "$deadline" ]; then
      echo "   ERROR: $s no quedó sano a tiempo. Últimas líneas del log:"; $COMPOSE logs --tail 40 "$s"; exit 1
    fi
    sleep 5
  done
  echo "   $s healthy"
done

echo ">> [4/4] Pruebas locales (dentro del Codespace)"
printf '   GET /health        (gateway)            -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/health)"
printf '   GET /api/health    (backend, sin token) -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/api/health)"
printf '   GET /api/orders    (sin token: 401)     -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/api/orders)"
printf '   GET /admin/queues  (sin token: 401)     -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/admin/queues)"
echo "   Clúster RabbitMQ (Running Nodes):"
$COMPOSE exec -T rabbitmq1 rabbitmqctl cluster_status 2>/dev/null \
  | awk '/^Running Nodes/{f=1;next} /^Versions/{f=0} f && /rabbit@/{print "     " $0}'

if [ -n "${CODESPACE_NAME:-}" ]; then
  echo
  echo ">> Visibilidad de puertos: gateway (8000) PÚBLICO, consola (15672) PRIVADA"
  if gh codespace ports visibility 8000:public 15672:private -c "$CODESPACE_NAME" >/dev/null 2>&1; then
    echo "   OK (gh codespace ports visibility)"
  else
    echo "   No se pudo cambiar con gh desde aquí. Hazlo en la pestaña PORTS: clic derecho en 8000 ->"
    echo "   Port Visibility -> Public (y deja 15672 en Private). Ver deploy/CODESPACES.md"
  fi
fi

cat <<EOF

======================================================================
  URL pública del gateway (pónla en el front como URL de la API):
    ${PUBLIC_URL}/api
  Rutas:  ${PUBLIC_URL}/api/...   y   ${PUBLIC_URL}/admin/...
  Consola de RabbitMQ (privada, solo tú): ${CONSOLE_URL}
    usuario: charme_admin   contraseña: grep RABBITMQ_PASSWORD .env
======================================================================
EOF
