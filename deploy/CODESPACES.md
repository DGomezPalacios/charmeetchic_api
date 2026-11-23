# Demo en GitHub Codespaces

Alternativa a la VM de Azure: el mismo `docker-compose.prod.yml` (clúster RabbitMQ de 2 nodos, PostgreSQL,
backend, rabbit-admin y gateway) corriendo dentro de un Codespace con Docker. Codespaces pone el HTTPS
(`https://<codespace>-8000.app.github.dev`), por eso aquí no va Caddy (`docker-compose.codespaces.yml`).

## 0. Antes de crear el Codespace: idle timeout de 240 min

El timeout se fija **al crear** el Codespace; cambiarlo después no afecta a uno ya creado.

github.com → foto de perfil → **Settings → Codespaces → Default idle timeout** = `240` → **Save**.

(Alternativa al crear desde tu PC: `gh codespace create -R DGomezPalacios/charmeetchic_api -b feature/rabbitmq -m largePremiumLinux --idle-timeout 240m`.)

## 1. Crear

Repo en GitHub → **Code → Codespaces → ⋯ → New with options** → Branch `feature/rabbitmq`, Machine type
**4-core** (el devcontainer pide 4 CPU / 16 GB) → **Create codespace**. La primera vez tarda ~3–5 min.

## 2. Levantar (en la terminal del Codespace)

```bash
./deploy/codespaces-up.sh
```

Genera `.env` (sin mostrar contraseñas), construye las 4 imágenes (API, rabbit-admin, kafka-admin, gateway;
~6–10 min la primera vez), levanta todo (RabbitMQ + PostgreSQL + **Kafka: 3 Zookeeper, 3 brokers y Kafka UI**),
espera a que esté sano, prueba `/health`, `/api/health`, 401 sin token, el clúster de RabbitMQ, el quórum de
Zookeeper y los tópicos de Kafka (3 particiones, RF 3), e imprime las URLs.

## 3. Visibilidad de puertos

| Puerto | Visibilidad | Por qué |
|---|---|---|
| 8000 (gateway) | **Public** | El front en Firebase Hosting lo llama desde el navegador; con *Private* GitHub pide login y bloquea las llamadas |
| 15672 (consola RabbitMQ) | **Private** | Solo tú (con tu sesión de GitHub) puedes abrirla |
| 8085 (Kafka UI) | **Private** | No tiene login propio: solo tú (con tu sesión de GitHub) puedes abrirla |

- **Pestaña PORTS** (abajo, junto a TERMINAL): clic derecho en `8000` → **Port Visibility → Public**.
  `15672` y `8085` quedan en Private (es el valor por defecto).
- **O con gh** (en el Codespace o en tu PC):
  ```bash
  gh codespace ports visibility 8000:public 15672:private 8085:private -c "$CODESPACE_NAME"   # dentro del Codespace
  gh codespace list                                                              # en tu PC: ver el nombre
  ```

Que el puerto sea público no deja la API abierta: el gateway exige un ID token de Firebase válido (401 si no).

## 4. Verificar

```bash
ENV_FILE=.env ./deploy/verificar.sh       # clúster, colas con sus argumentos, endpoints, certificado
```

Consola: pestaña PORTS → globo de la fila 15672 → usuario `charme_admin`, contraseña `grep RABBITMQ_PASSWORD .env`.

Kafka UI: pestaña PORTS → globo de la fila 8085 (tópicos, particiones/ISR, mensajes con headers del `.DLT` y lag
de cada consumer group). Demo de Kafka: carpeta **"10. Kafka"** de la colección de Postman (ver README, sección
"Streaming con Kafka").

```bash
C="docker compose -f docker-compose.prod.yml -f docker-compose.kafka.yml -f docker-compose.codespaces.yml"
$C ps                                                            # 14 servicios, todos healthy
$C exec kafka1 kafka-topics --bootstrap-server localhost:9092 --describe --topic ventas.eventos
$C logs -f backend | grep -E "Kafka|Monitor de stock"           # productor, reintentos, DLT
```

## 5. Front

En el panel Angular (Firebase Hosting), la URL de la API es `https://<codespace>-8000.app.github.dev/api`.
CORS ya permite `https://charme-et-chic.web.app`, `https://charme-et-chic.firebaseapp.com` y `http://localhost:4200`.

## Apagar / volver a encender

- Se detiene solo tras el idle timeout. Para detenerlo a mano: Code → Codespaces → ⋯ → **Stop codespace**.
- Al volver a abrirlo, los contenedores tienen `restart: unless-stopped` y vuelven a subir con Docker; si no,
  `./deploy/codespaces-up.sh` (reutiliza `.env` y las imágenes, ~2 min).
- Detenido no consume horas de cómputo (sí almacenamiento).
