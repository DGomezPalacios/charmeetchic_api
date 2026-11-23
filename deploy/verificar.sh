#!/usr/bin/env bash
# =============================================================================
# Verifica el despliegue: contenedores, clúster de RabbitMQ, colas y endpoints públicos.
#
#   ./deploy/verificar.sh            (en la VM, desde la raíz del repo, con el compose arriba)
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
ENV_FILE="${ENV_FILE:-.env}"   # otro archivo: ENV_FILE=/ruta/.env ./deploy/verificar.sh
COMPOSE="docker compose --env-file $ENV_FILE -f docker-compose.prod.yml"
DOMAIN="$(grep -E "^DOMAIN=" "$ENV_FILE" | cut -d= -f2-)"
CURL_OPTS=(-s --max-time 10)
[ "$DOMAIN" = "localhost" ] && CURL_OPTS+=(-k)   # en el PC Caddy usa un certificado local

titulo() { printf '\n==== %s\n' "$1"; }

titulo "Contenedores (todos deberían estar 'healthy' o 'running')"
$COMPOSE ps --format 'table {{.Service}}\t{{.Status}}'

titulo "Clúster RabbitMQ visto desde el nodo 1 (deben aparecer rabbit@rabbitmq1 y rabbit@rabbitmq2 en 'Running Nodes')"
$COMPOSE exec -T rabbitmq1 rabbitmqctl cluster_status | sed -n "/^Disk Nodes/,/^Versions/p;/^Network Partitions/,/^Listeners/p" | grep -v -E "^(Versions|Listeners)$"

titulo "Colas: tipo, nodo que las aloja (leader), mensajes y consumidores"
$COMPOSE exec -T rabbitmq1 rabbitmqctl list_queues --quiet name type leader messages consumers

titulo "Endpoints públicos por HTTPS (Caddy -> gateway)"
printf 'GET https://%s/health      (gateway)            -> ' "$DOMAIN"
curl "${CURL_OPTS[@]}" -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/health"
printf 'GET https://%s/api/health  (backend, sin token) -> ' "$DOMAIN"
curl "${CURL_OPTS[@]}" -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/api/health"
printf 'GET https://%s/api/orders  (sin token: 401)     -> ' "$DOMAIN"
curl "${CURL_OPTS[@]}" -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/api/orders"
printf 'GET https://%s/admin/queues (sin token: 401)    -> ' "$DOMAIN"
curl "${CURL_OPTS[@]}" -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/admin/queues"

titulo "Certificado HTTPS"
echo | openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" 2>/dev/null \
  | openssl x509 -noout -issuer -subject -dates 2>/dev/null || echo "No se pudo leer el certificado"

titulo "Memoria por contenedor"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
