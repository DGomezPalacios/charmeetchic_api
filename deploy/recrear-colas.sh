#!/usr/bin/env bash
# =============================================================================
# Borra las colas de la aplicación para que el backend las vuelva a declarar con los argumentos ACTUALES
# del yml (retención: x-max-length, x-message-ttl, x-overflow...).
#
# ¿Por qué hace falta? RabbitMQ no deja cambiar los argumentos de una cola que ya existe: al declararla con
# otros valores responde PRECONDITION_FAILED y la app no puede usarla. Se borra y se vuelve a crear.
#
# ⚠ Se PIERDEN los mensajes que estén en esas colas (incluidas las DLQ). Úsalo antes de la demo, no durante.
#
#   PC (docker-compose.yml):   ./deploy/recrear-colas.sh
#   VM (docker-compose.prod):  RABBIT_USER=charme_admin RABBIT_PASS=<clave> ./deploy/recrear-colas.sh
#   y luego reiniciar el backend:  docker compose [-f docker-compose.prod.yml] restart backend
#   (en el PC, si la API corre con mvn spring-boot:run, basta con detenerla y volver a lanzarla)
# =============================================================================
set -euo pipefail

RABBIT_URL="${RABBIT_URL:-http://localhost:15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
PATTERN='^(inventario|notificaciones|ventas)\.'

QUEUES=$(curl -fsS -u "$RABBIT_USER:$RABBIT_PASS" "$RABBIT_URL/api/queues/%2F?columns=name" \
  | grep -o '"name":"[^"]*"' | cut -d'"' -f4 | grep -E "$PATTERN" || true)

if [ -z "$QUEUES" ]; then
  echo "No hay colas de la aplicación en $RABBIT_URL. Nada que borrar."
  exit 0
fi

echo "Se borrarán estas colas (y sus mensajes) en $RABBIT_URL:"
echo "$QUEUES" | sed 's/^/  - /'
read -r -p "¿Continuar? [s/N] " answer
[ "$answer" = "s" ] || [ "$answer" = "S" ] || { echo "Cancelado."; exit 1; }

for q in $QUEUES; do
  curl -fsS -o /dev/null -u "$RABBIT_USER:$RABBIT_PASS" -X DELETE "$RABBIT_URL/api/queues/%2F/$q" && echo "borrada: $q"
done
echo
echo "Listo. Reinicia el backend para que declare las colas con la retención nueva."
