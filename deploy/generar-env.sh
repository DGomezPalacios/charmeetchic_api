#!/usr/bin/env bash
# =============================================================================
# Crea el archivo .env con contraseñas y Erlang cookie ALEATORIAS (nada de guest/guest ni claves débiles).
#
#   ./deploy/generar-env.sh charmeetchic-demo.eastus.cloudapp.azure.com https://charme-et-chic.web.app
#
# No sobrescribe un .env existente. Las contraseñas quedan solo en este archivo (permisos 600):
# anótalas en un lugar seguro si las necesitas para la consola de RabbitMQ.
# =============================================================================
set -euo pipefail

DOMAIN="${1:?Uso: $0 <dominio> <origen-del-front>   (ej: $0 x.eastus.cloudapp.azure.com https://charme-et-chic.web.app)}"
FRONTEND_ORIGIN="${2:?Falta el origen del front, p. ej. https://charme-et-chic.web.app}"
cd "$(dirname "$0")/.."

if [ -e .env ]; then
  echo ".env ya existe. Si quieres regenerarlo, bórralo primero (¡cambiarán las contraseñas!)." >&2
  exit 1
fi

umask 077
cat > .env <<EOF
DOMAIN=${DOMAIN}
FRONTEND_ORIGIN=${FRONTEND_ORIGIN%/}
FIREBASE_PROJECT_ID=charme-et-chic
RABBITMQ_USERNAME=charme_admin
RABBITMQ_PASSWORD=$(openssl rand -hex 16)
RABBITMQ_ERLANG_COOKIE=$(openssl rand -hex 32)
RABBITMQ_CONSOLE_PORT=15672
POSTGRES_DB=charmeetchic
POSTGRES_USER=charmeetchic
POSTGRES_PASSWORD=$(openssl rand -hex 16)
MESSAGING_SIMULATED_FAILURES=true
EOF

echo ".env creado (permisos 600). Usuario de la consola de RabbitMQ: charme_admin"
echo "Contraseña de la consola:  grep RABBITMQ_PASSWORD .env"
