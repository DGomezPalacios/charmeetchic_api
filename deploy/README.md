# Despliegue en Azure (una VM con Docker)

Todo el sistema corre en **una VM Ubuntu 24.04 (B2s, 2 vCPU, 4 GB)** con `docker-compose.prod.yml`:

```
Navegador (Firebase Hosting, HTTPS)
        │  https://<nombre>.<region>.cloudapp.azure.com
        ▼
   caddy :443  ── certificado automático de Let's Encrypt
        ▼
   gateway :8000 ── valida el ID token de Firebase (401 si no), CORS, rutas
        ├── /api/**   ─► backend :8080 ──► postgres :5432
        │                    └──────────► rabbitmq1 ◄═ clúster ═► rabbitmq2
        └── /admin/** ─► rabbit-admin :8081 ─┘
```

Solo están abiertos a Internet el **80 y el 443** (Caddy). La consola de RabbitMQ (**15672**) se abre solo
para tu IP. Postgres, AMQP (5672) y los servicios Java no tienen puertos publicados.

| Contenedor | mem_limit | JVM `-Xmx` |
|---|---|---|
| rabbitmq1 / rabbitmq2 | 512m c/u | — (watermark 320 MiB) |
| postgres | 384m | — |
| backend | 768m | 448m |
| rabbit-admin | 384m | 224m |
| gateway | 384m | 224m |
| caddy | 128m | — |
| **Total** | **3072 MiB** | (~900 MiB quedan para el sistema operativo) |

---

## 1. Crear la VM (una vez, desde tu PC)

**Opción A — Azure CLI** (Git Bash con `az` instalado, o Azure Cloud Shell en modo Bash):

```bash
az login
DNS_LABEL=charmeetchic-demo LOCATION=eastus ./deploy/azure-crear-vm.sh
```

El script crea el grupo de recursos, la VM con IP pública y etiqueta DNS, y estas reglas del NSG:

| Regla | Puerto | Origen |
|---|---|---|
| default-allow-ssh | 22 | solo tu IP |
| allow-http | 80 | Internet (Let's Encrypt lo necesita) |
| allow-https | 443 | Internet |
| allow-rabbitmq-console-my-ip | 15672 | solo tu IP |

**Opción B — Portal:** Máquinas virtuales → Crear → Ubuntu Server 24.04 LTS, tamaño **B2s**, autenticación
con clave SSH. En *Redes*, deja crear la IP pública. Luego:
- IP pública → Configuración → **Etiqueta de nombre DNS** (p. ej. `charmeetchic-demo`). Ese es tu `DOMAIN`.
- Red → Reglas de entrada: agrega 80 y 443 (cualquier origen), 15672 (origen = tu IP) y deja el 22
  restringido a tu IP.

> La cuenta de estudiante a veces limita regiones o tamaños. Si `az vm create` falla por política o
> capacidad, prueba `LOCATION=eastus2`, `westus2` o `brazilsouth`.

## 2. Preparar la VM

```bash
ssh azureuser@<DOMAIN>
git clone https://github.com/DGomezPalacios/charmeetchic_api.git
cd charmeetchic_api
git checkout feature/rabbitmq          # o develop, cuando esté fusionado
sudo ./deploy/instalar-docker.sh       # swap de 2 GB + Docker; luego: exit y volver a entrar por ssh
```

## 3. Crear el .env (NO se versiona)

```bash
cd charmeetchic_api
./deploy/generar-env.sh <DOMAIN> https://charme-et-chic.web.app
cat .env                               # revisa DOMAIN y FRONTEND_ORIGIN
```

Genera contraseñas y la Erlang cookie al azar. El usuario de la consola de RabbitMQ es `charme_admin` y su
contraseña está en `RABBITMQ_PASSWORD`. (Alternativa manual: `cp .env.example .env` y editarlo.)

## 4. Levantar

```bash
docker compose -f docker-compose.prod.yml build      # 1ª vez: ~10 min en la B2s (compila 3 apps con Maven)
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps         # esperar a que todo diga (healthy)
docker compose -f docker-compose.prod.yml logs -f caddy   # ver que obtiene el certificado
```

Orden de arranque (lo controla `depends_on`): postgres y rabbitmq1 → rabbitmq2 (se une al 1) → backend
y rabbit-admin → gateway → caddy.

## 5. Verificar

```bash
./deploy/verificar.sh
```

Revisa los contenedores, `rabbitmqctl cluster_status` (deben aparecer **rabbit@rabbitmq1 y rabbit@rabbitmq2**
en *Running Nodes*), las colas y en qué nodo están, los endpoints por HTTPS y el certificado.

Consola: `http://<DOMAIN>:15672` → usuario `charme_admin` → pestaña **Overview → Nodes**: los 2 nodos.

Con un token real de Firebase (en la consola del navegador del panel Angular:
`await getAuth().currentUser.getIdToken()`):

```bash
T="<token>"
curl https://<DOMAIN>/api/products -H "Authorization: Bearer $T"
curl https://<DOMAIN>/admin/queues -H "Authorization: Bearer $T"      # requiere claim ADMIN
curl -X POST https://<DOMAIN>/api/reports/inventory/async -H "Authorization: Bearer $T" \
     -H "Content-Type: application/json" -d '{"simularError":"TRANSITORIO"}'
```

En el frontend (Firebase Hosting) la URL de la API pasa a ser `https://<DOMAIN>/api`.

## Operación

| Tarea | Comando |
|---|---|
| Logs de un servicio | `docker compose -f docker-compose.prod.yml logs -f backend` |
| Actualizar a la última versión | `git pull && docker compose -f docker-compose.prod.yml up -d --build` |
| Reiniciar uno | `docker compose -f docker-compose.prod.yml restart backend` |
| Apagar (conserva datos) | `docker compose -f docker-compose.prod.yml down` |
| Borrar TODO (datos incluidos) | `docker compose -f docker-compose.prod.yml down -v` (¡borra también el certificado!) |
| Ahorrar crédito | desde tu PC: `az vm deallocate -g rg-charmeetchic -n vm-charmeetchic` |

## Problemas típicos

- **Caddy no obtiene el certificado**: el 80 y el 443 deben estar abiertos a Internet en el NSG y `DOMAIN`
  debe ser exactamente la etiqueta DNS de la IP pública. Revisa `docker compose ... logs caddy`. No borres el
  volumen `caddy-data`: Let's Encrypt limita los certificados por semana.
- **CORS en el navegador**: `FRONTEND_ORIGIN` debe ser el origen exacto del front (`https://...web.app`, sin `/`
  final). Tras cambiar el .env: `docker compose -f docker-compose.prod.yml up -d gateway`.
- **401 desde el gateway con un token válido**: el token dura 1 hora; pide uno nuevo.
- **Memoria**: `docker stats`. Si un contenedor se reinicia solo, revisa `docker inspect <contenedor>` → `OOMKilled`.

## Alcance del clúster (para la defensa)

- Los 2 nodos comparten usuarios, exchanges, colas y bindings: la consola de cualquiera ve todo.
- El backend conoce los dos (`RABBITMQ_ADDRESSES=rabbitmq1:5672,rabbitmq2:5672`): si al conectar el 1 no
  responde, usa el 2.
- Las colas son **classic** y viven en el nodo donde se declararon (rabbitmq1). Si se cae el nodo 1, esas colas
  quedan no disponibles hasta que vuelva. Para tolerar la caída de un nodo se usan **colas quorum**, que
  necesitan una mayoría de nodos: con 2 nodos la mayoría son los 2, así que no ganarían nada. Lo correcto
  serían 3 nodos, que no caben en una B2s junto con el resto.
