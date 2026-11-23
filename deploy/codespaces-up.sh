#!/usr/bin/env bash
# =============================================================================
# Levanta todo el sistema en GitHub Codespaces (o en un PC con Docker):
#   1. genera .env con contraseñas aleatorias (si no existe; no muestra contraseñas)
#   2. construye y levanta docker-compose.prod.yml + docker-compose.kafka.yml + docker-compose.codespaces.yml
#      (RabbitMQ 2 nodos, PostgreSQL, API, rabbit-admin, gateway + Kafka 3 ZK/3 brokers, Kafka UI, kafka-admin)
#   3. espera a que todo esté sano, prueba los endpoints y muestra la URL pública del gateway
#
#   ./deploy/codespaces-up.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose.prod.yml -f docker-compose.kafka.yml -f docker-compose.codespaces.yml"
FRONT="https://charme-et-chic.web.app,https://charme-et-chic.firebaseapp.com"

if [ -n "${CODESPACE_NAME:-}" ] && [ -n "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}" ]; then
  HOST="${CODESPACE_NAME}-8000.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
  CONSOLE_URL="https://${CODESPACE_NAME}-15672.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
  KAFKA_UI_URL="https://${CODESPACE_NAME}-8085.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
  PUBLIC_URL="https://${HOST}"
else
  echo "(No estás en un Codespace: se usará http://localhost:8000)"
  HOST="localhost"
  CONSOLE_URL="http://localhost:15672"
  KAFKA_UI_URL="http://localhost:8085"
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
for s in backend rabbit-admin kafka-admin gateway; do
  start=$(date +%s)
  $COMPOSE build "$s" > "/tmp/build-$s.log" 2>&1 || { echo "   ERROR construyendo $s:"; tail -30 "/tmp/build-$s.log"; exit 1; }
  echo "   $s OK ($(( $(date +%s) - start )) s)"
done

echo ">> [3/4] Levantando el stack"
$COMPOSE up -d 2>&1 | tail -2

echo "   Esperando a que Kafka (3 brokers), el backend, los admin y el gateway estén sanos (máx. 7 min)..."
deadline=$(( $(date +%s) + 420 ))
for s in kafka1 kafka2 kafka3 backend rabbit-admin kafka-admin gateway kafka-ui; do
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
printf '   GET /kafka-admin/topics (sin token: 401) -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/kafka-admin/topics)"
echo "   Ensemble Zookeeper (Mode):"
for z in zookeeper1 zookeeper2 zookeeper3; do
  printf '     %s: %s\n' "$z" "$($COMPOSE exec -T "$z" bash -c 'exec 3<>/dev/tcp/localhost/2181; echo srvr >&3; grep Mode <&3' 2>/dev/null)"
done
echo "   Tópicos de Kafka (particiones / RF):"
$COMPOSE exec -T kafka1 bash -c 'KAFKA_HEAP_OPTS=-Xmx64m kafka-topics --bootstrap-server localhost:9092 --describe 2>/dev/null' \
  | awk '/PartitionCount/ && $2 !~ /^__/ {printf "     %-24s particiones=%s RF=%s\n", $2, $6, $8}'

if [ -n "${CODESPACE_NAME:-}" ]; then
  echo
  echo ">> Visibilidad de puertos: gateway (8000) PÚBLICO; consola RabbitMQ (15672) y Kafka UI (8085) PRIVADOS"
  if gh codespace ports visibility 8000:public 15672:private 8085:private -c "$CODESPACE_NAME" >/dev/null 2>&1; then
    echo "   OK (gh codespace ports visibility)"
  else
    echo "   No se pudo cambiar con gh desde aquí. Hazlo en la pestaña PORTS: clic derecho en 8000 ->"
    echo "   Port Visibility -> Public (y deja 15672 y 8085 en Private). Ver deploy/CODESPACES.md"
  fi
fi

cat <<EOF

======================================================================
  URL pública del gateway (pónla en el front como URL de la API):
    ${PUBLIC_URL}/api
  Rutas:  ${PUBLIC_URL}/api/...   ${PUBLIC_URL}/admin/...   ${PUBLIC_URL}/kafka-admin/...
  Consola de RabbitMQ (privada, solo tú): ${CONSOLE_URL}
    usuario: charme_admin   contraseña: grep RABBITMQ_PASSWORD .env
  Kafka UI (privada, solo tú): ${KAFKA_UI_URL}
  Dashboard de streaming: GET ${PUBLIC_URL}/api/streaming/metrics (token ADMIN)
======================================================================
EOF
