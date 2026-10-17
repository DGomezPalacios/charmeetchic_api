#!/usr/bin/env bash
# =============================================================================
# Prepara la VM Ubuntu 24.04: swap + Docker Engine + plugin compose. Se ejecuta UNA vez EN LA VM:
#
#   sudo ./deploy/instalar-docker.sh
#   (cerrar la sesión SSH y volver a entrar para usar docker sin sudo)
# =============================================================================
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Ejecuta con sudo: sudo $0" >&2
  exit 1
fi
TARGET_USER="${SUDO_USER:-azureuser}"

# Swap de 2 GB: la VM tiene 4 GB y compilar las 3 imágenes con Maven mientras corren los contenedores
# puede pasarse de memoria. El swap evita que el kernel mate procesos en ese momento.
if ! swapon --show | grep -q '/swapfile'; then
  echo ">> Creando swap de 2 GB"
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

if ! command -v docker >/dev/null 2>&1; then
  echo ">> Instalando Docker Engine (script oficial de Docker)"
  curl -fsSL https://get.docker.com | sh
fi

usermod -aG docker "$TARGET_USER"
systemctl enable --now docker

docker --version
docker compose version
echo
echo "Listo. Sal de la sesión SSH (exit) y vuelve a entrar para usar 'docker' sin sudo."
