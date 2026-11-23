#!/usr/bin/env bash
# =============================================================================
# Crea la VM de Azure y abre los puertos en el NSG. Se ejecuta UNA vez desde tu PC (Git Bash con
# Azure CLI instalado y `az login` hecho) o desde Azure Cloud Shell (Bash), que ya trae az.
#
#   ./deploy/azure-crear-vm.sh                       # usa los valores por defecto de abajo
#   DNS_LABEL=charme-daniela LOCATION=eastus2 ./deploy/azure-crear-vm.sh
#
# Al terminar imprime el dominio <DNS_LABEL>.<LOCATION>.cloudapp.azure.com para poner en .env (DOMAIN).
# =============================================================================
set -euo pipefail

RG="${RG:-rg-charmeetchic}"
LOCATION="${LOCATION:-eastus}"          # la cuenta de estudiante a veces solo permite algunas regiones
VM="${VM:-vm-charmeetchic}"
SIZE="${SIZE:-Standard_B2s}"            # 2 vCPU, 4 GB RAM
DNS_LABEL="${DNS_LABEL:-charmeetchic-demo}"   # debe ser único en la región
ADMIN_USER="${ADMIN_USER:-azureuser}"
# Tu IP pública: solo ella podrá entrar por SSH y ver la consola de RabbitMQ.
MY_IP="${MY_IP:-$(curl -s https://api.ipify.org)}"
NSG="${VM}NSG"                          # nombre que az vm create le pone al NSG

echo ">> Grupo de recursos $RG en $LOCATION"
az group create --name "$RG" --location "$LOCATION" --output none

echo ">> VM $VM ($SIZE, Ubuntu 24.04) con IP pública y etiqueta DNS '$DNS_LABEL'"
az vm create \
  --resource-group "$RG" --name "$VM" \
  --image Ubuntu2404 --size "$SIZE" \
  --admin-username "$ADMIN_USER" --generate-ssh-keys \
  --public-ip-sku Standard --public-ip-address-dns-name "$DNS_LABEL" \
  --os-disk-size-gb 30 \
  --nsg-rule SSH \
  --output none

echo ">> Reglas del NSG"
# SSH: solo desde tu IP (az vm create la deja abierta a todo Internet).
az network nsg rule update --resource-group "$RG" --nsg-name "$NSG" --name default-allow-ssh \
  --source-address-prefixes "$MY_IP/32" --output none
# 80 y 443 abiertos a Internet: Let's Encrypt valida el dominio por el 80 y el front entra por el 443.
az network nsg rule create --resource-group "$RG" --nsg-name "$NSG" --name allow-http \
  --priority 1010 --protocol Tcp --destination-port-ranges 80 --access Allow --output none
az network nsg rule create --resource-group "$RG" --nsg-name "$NSG" --name allow-https \
  --priority 1020 --protocol Tcp --destination-port-ranges 443 --access Allow --output none
# Consola de RabbitMQ: SOLO tu IP.
az network nsg rule create --resource-group "$RG" --nsg-name "$NSG" --name allow-rabbitmq-console-my-ip \
  --priority 1030 --protocol Tcp --destination-port-ranges 15672 --source-address-prefixes "$MY_IP/32" \
  --access Allow --output none

FQDN=$(az vm show -d --resource-group "$RG" --name "$VM" --query fqdns --output tsv)
IP=$(az vm show -d --resource-group "$RG" --name "$VM" --query publicIps --output tsv)
cat <<EOF

Listo.
  Dominio (DOMAIN en .env): $FQDN
  IP pública:               $IP
  Tu IP permitida (SSH y 15672): $MY_IP

Siguiente paso:
  ssh $ADMIN_USER@$FQDN
  (en la VM) seguir deploy/README.md desde "2. Preparar la VM".

Si tu IP cambia (otra red/wifi), actualiza las reglas:
  az network nsg rule update -g $RG --nsg-name $NSG -n default-allow-ssh --source-address-prefixes <IP>/32
  az network nsg rule update -g $RG --nsg-name $NSG -n allow-rabbitmq-console-my-ip --source-address-prefixes <IP>/32

Para no gastar crédito cuando no la uses:  az vm deallocate -g $RG -n $VM   (y az vm start para volver)
EOF
