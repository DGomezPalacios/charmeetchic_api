# charmeetchic-api

API REST para el **control de inventario** de la tienda de accesorios y joyas **Charme et Chic**, diseñada para
crecer hacia un ecommerce (carrito, checkout, pagos).

- **Stack:** Java 17+ · Spring Boot 3.2 · Spring Security · Spring Data JPA · H2 (desarrollo) / PostgreSQL
  (producción, perfil `prod`) · RabbitMQ · JWT · Maven
- **Autenticación:** **Firebase Authentication**. La API valida los *ID tokens* de Firebase (firma RS256 con las claves
  públicas de Google, emisor, audiencia, `sub` y expiración)
- **Autorización:** roles `ADMIN` y `USER` (custom claims de Firebase)
- **URL base:** `http://localhost:8080/api`

---

## Cómo ejecutar

Requisitos: **JDK 17 o superior** y **Maven 3.9+**.

```bash
mvn spring-boot:run
```

| Qué | URL |
|---|---|
| API | http://localhost:8080/api |
| Health check | http://localhost:8080/api/health |
| Catálogo (público) | http://localhost:8080/api/products |
| Consola H2 | http://localhost:8080/api/h2-console |

Consola H2: JDBC URL `jdbc:h2:mem:testdb`, usuario `sa`, contraseña vacía.

Al arrancar se cargan 5 categorías y 15 productos de ejemplo (`src/main/resources/data.sql`, con
instrucciones para agregar más). La base de datos es **en memoria**: se borra al detener la aplicación.

Otros comandos:

```bash
mvn test                 # tests unitarios + integración
mvn package              # genera target/charmeetchic-api-0.0.1-SNAPSHOT.jar
java -jar target/charmeetchic-api-0.0.1-SNAPSHOT.jar
```

> **Antes de usar los endpoints protegidos** configura tu Project ID de Firebase (ver
> [Configurar Firebase](#configurar-firebase-authentication)). Sin él la aplicación arranca igual con el valor por
> defecto `charmeetchic`, que solo aceptará tokens de un proyecto con ese ID. Los endpoints públicos (catálogo,
> categorías, health) funcionan siempre. Para probar sin un proyecto de Firebase, ver
> [Probar sin Firebase](#probar-sin-firebase-modo-local).

---

## Endpoints principales

Todas las rutas cuelgan de `/api` (context-path). Errores y paginación: ver secciones más abajo.

### Productos

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| GET | `/api/products?page=0&size=10&sort=name,desc` | Público | Catálogo paginado (solo productos activos) |
| GET | `/api/products/search?q=perla&category=1&minPrice=10000&maxPrice=20000` | Público | Búsqueda por texto (nombre/descripción), categoría y/o rango de precio (`minPrice`/`maxPrice`, ambos opcionales e inclusive); `400` si `minPrice > maxPrice` |
| GET | `/api/products/{id}` | Público | Detalle de un producto activo |
| GET | `/api/products/low-stock` | ADMIN | Productos activos con stock bajo el umbral (por defecto 5) |
| POST | `/api/products` | ADMIN | Crear producto → `201` + cabecera `Location` |
| PUT | `/api/products/{id}` | ADMIN | Actualizar producto (ver *control de concurrencia*) |
| DELETE | `/api/products/{id}` | ADMIN | Borrado **lógico** (`active=false`) → `204` |

### Categorías

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/api/categories`, `/api/categories/{id}` | Público |
| POST | `/api/categories` | ADMIN |
| PUT | `/api/categories/{id}` | ADMIN |
| DELETE | `/api/categories/{id}` | ADMIN (`409` si tiene productos) |

### Órdenes

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| POST | `/api/orders` | Autenticado | Crea una orden `PENDING` para el usuario del token y **descuenta stock** |
| GET | `/api/orders` | Autenticado | Mis órdenes (más reciente primero) |
| GET | `/api/orders/{id}` | Autenticado | Una orden mía (`403` si es de otro; ADMIN puede ver cualquiera) |
| PUT | `/api/orders/{id}/status` | ADMIN | Avanza el estado: `PENDING → PROCESSING → SENT → DELIVERED` |
| DELETE | `/api/orders/{id}` | Autenticado | Cancela una orden mía **solo si está `PENDING`**; devuelve el stock |

### Reportes (todos ADMIN)

Los reportes con periodo aceptan `from` y `to` (`yyyy-MM-dd`, ambos inclusive). Sin ellos: últimos 30 días.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/reports/sales?from=&to=` | Nº de órdenes, subtotal, IVA, total y ticket promedio |
| GET | `/api/reports/sales-by-category?from=&to=` | Unidades e ingresos por categoría |
| GET | `/api/reports/inventory-value` | Valor del inventario a costo y a precio de venta, utilidad potencial |
| GET | `/api/reports/top-products?from=&to=&limit=10` | Productos más vendidos (`limit` 1–50) |
| POST | `/api/reports/inventory/async` | Pide un reporte de inventario en segundo plano (RabbitMQ). Responde **202** con el `id` y `Location` |
| GET | `/api/reports/async/{id}` | Estado del reporte: `PENDIENTE`, `COMPLETADO` (con `result`) o `FALLIDO` (con `error`) |
| GET | `/api/reports/stock-movements?productId=&page=&size=` | Auditoría de movimientos de stock (la escribe el consumidor de RabbitMQ) |

### Otros

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| GET | `/api/health` | Público | `{"status":"UP", "service":"...", "version":"0.0.1-SNAPSHOT", "timestamp":"..."}` |
| GET | `/api/profile` | Autenticado | Datos y roles del usuario del token |

### Ejemplos

`$ID_TOKEN` es el ID token de Firebase del usuario (ver [Conectar con el frontend](#conectar-con-el-frontend-angular)).

```bash
# Catálogo público
curl "http://localhost:8080/api/products?page=0&size=5&sort=price,desc"

# Crear un producto (ADMIN)
curl -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer $ID_TOKEN_ADMIN" -H "Content-Type: application/json" \
  -d '{
        "name": "Collar Corazón Oro",
        "description": "Baño de oro 18k",
        "price": 22990.00,
        "cost": 9500.00,
        "sku": "COL-COR-010",
        "stock": 12,
        "categoryId": 1,
        "images": ["/images/products/collar-corazon-oro.jpg"]
      }'

# Comprar — el precio lo pone el servidor, el usuario sale del token
curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $ID_TOKEN" -H "Content-Type: application/json" \
  -d '{
        "shippingAddress": "Av. Providencia 1234, Santiago",
        "items": [ { "productId": 1, "quantity": 2 }, { "productId": 4, "quantity": 1 } ]
      }'

# Avanzar una orden (ADMIN)
curl -X PUT http://localhost:8080/api/orders/1/status \
  -H "Authorization: Bearer $ID_TOKEN_ADMIN" -H "Content-Type: application/json" \
  -d '{ "status": "PROCESSING" }'
```

### Reglas de negocio importantes

- **Precios:** son **netos**. Al crear la orden: `subtotal = Σ precio × cantidad`, `tax = subtotal × tasa` (19 % por
  defecto, configurable), `total = subtotal + tax`. El precio de cada línea se **congela** en la orden.
- **Stock:** se descuenta al crear la orden y se devuelve al cancelarla. Comprar más que el stock → `409`.
- **Concurrencia:** `Product` tiene `@Version` (bloqueo optimista). Si dos compras compiten por la última unidad, una
  recibe `409`. En `PUT /products/{id}` puedes enviar el campo `version` que leíste: si otro admin modificó el
  producto entretanto, recibes `409` en vez de pisar su cambio.
- **Borrado de productos:** lógico. Un producto inactivo desaparece del catálogo público (404), pero las órdenes
  históricas lo siguen referenciando. Para reactivarlo: `PUT` con `"active": true`.
- **SKU:** único; se normaliza a MAYÚSCULAS. **Categorías:** nombre único (sin distinguir mayúsculas).
- **Estados de orden:** solo avanzan de a un paso; no hay retroceso ni saltos.

### Formato de errores

Todos los errores (incluidos 401/403) usan el mismo cuerpo:

```json
{
  "timestamp": "2026-09-20T21:10:31.512Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Datos de entrada inválidos",
  "path": "/api/products",
  "fieldErrors": { "price": "El precio debe ser mayor que 0", "sku": "El SKU es obligatorio" }
}
```

| Código | Cuándo |
|---|---|
| 400 | Validación (`fieldErrors`), JSON mal formado, parámetro inválido, transición de estado inválida |
| 401 | Token ausente/inválido/expirado (`WWW-Authenticate: Bearer`). Ver mensajes abajo |
| 403 | Rol insuficiente u orden de otro usuario |
| 404 | Recurso inexistente |
| 409 | SKU/nombre duplicado, stock insuficiente, conflicto de versión, orden no cancelable, categoría con productos |
| 500 | Error inesperado (el detalle se registra en el log, no se expone) |

Mensajes de `401` específicos de Firebase:

| `message` | Causa |
|---|---|
| `Autenticación requerida: envía el ID token de Firebase como Bearer` | Falta la cabecera `Authorization` en un endpoint protegido |
| `El token de Firebase ha expirado; obtén uno nuevo con getIdToken()` | Los ID tokens duran 1 hora |
| `El token no pertenece a este proyecto de Firebase (issuer inválido)` / `(audience inválida)` | Token de otro proyecto, o `FIREBASE_PROJECT_ID` mal configurado |
| `Token de Firebase inválido` | Firma incorrecta, token mal formado o sin firmar |
| `Algoritmo de firma no permitido: ...` | El token no está firmado con RS256 |
| `Clave de firma desconocida para este token` | `kid` que Google no publica |
| `El token no contiene el UID de Firebase (sub)` | Falta `sub` |

### Paginación

`GET /api/products?page=0&size=10&sort=name,desc` devuelve un `Page<ProductDTO>` de Spring Data
(`content`, `totalElements`, `totalPages`, `number`, `size`...). Tamaño máximo de página: 100. Un campo de
ordenamiento inexistente responde `400`.

---

## Seguridad y roles

| Rol | Puede |
|---|---|
| *(anónimo)* | Health, catálogo y categorías (solo lectura) |
| `USER` | Lo anterior + crear/consultar/cancelar **sus** órdenes + `/profile` |
| `ADMIN` | Todo lo de `USER` + escritura de catálogo y categorías, estados de orden, stock bajo y reportes |

- Todo usuario autenticado en Firebase es al menos `USER`; un `ADMIN` también tiene `USER`.
- Sesiones **stateless**, CSRF desactivado (no hay cookies), CORS solo para los orígenes configurados.
- Un token inválido responde `401` **incluso en endpoints públicos** (indica un cliente mal configurado). Como
  `getIdToken()` renueva el token solo, el frontend no debería toparse con esto.
- La primera vez que llega un token válido se registra el usuario en la tabla `users` (`GET /api/profile` lo devuelve).
  `userId` es el **UID de Firebase** (claim `sub`).

### Qué valida el backend en cada token

La verificación sigue el checklist oficial de Firebase para ID tokens y se hace en `JwtService`:

| Comprobación | Valor esperado |
|---|---|
| Firma | **RS256**, con la clave pública de Google cuyo `kid` coincide (JWKS de `securetoken@system.gserviceaccount.com`, cacheado) |
| `iss` | `https://securetoken.google.com/<PROJECT_ID>` |
| `aud` | `<PROJECT_ID>` |
| `sub` | UID de Firebase, no vacío |
| `exp` | presente y en el futuro |
| `iat`, `auth_time` | no en el futuro |

> **La firma se verifica siempre.** Decodificar el token sin comprobar la firma permitiría que cualquiera fabrique un
> token con el `iss`/`aud` correctos y se haga pasar por ADMIN. No hace falta el Admin SDK ni credenciales: las claves de
> firma de Google son públicas.

### Configurar Firebase Authentication

1. **Crear el proyecto:** [Firebase Console](https://console.firebase.google.com) → *Add project*.
2. **Project ID:** *Project settings* → *General* → **Project ID** (p. ej. `charmeetchic-a1b2c`; no es el *nombre* ni el
   *Project number*). Configúralo con la variable de entorno `FIREBASE_PROJECT_ID` o en `application.yml`
   (`firebase.project-id`).
3. **Habilitar el login:** *Authentication* → *Sign-in method* → activa Email/Password, Google, etc.
4. **Registrar la app web:** *Project settings* → *Your apps* → *Web* → copia el `firebaseConfig` para Angular.
5. **Asignar roles (ver siguiente sección).**

### Roles con custom claims

Los roles viajan **dentro del ID token** como *custom claims*, que solo puede escribir alguien con credenciales de
administrador (el cliente no puede modificarlos). El backend acepta cualquiera de estas formas:

```json
{ "roles": ["ADMIN"] }      // recomendada
{ "role": "ADMIN" }
{ "admin": true }
{ "ADMIN": true }           // el que asigna addAdmin.js (solo cuenta el booleano true)
```

Sin claims (o con valores desconocidos) el usuario es `USER`. Para convertir a alguien en ADMIN usa el **Admin SDK**
desde una máquina de confianza (nunca desde el frontend ni desde esta API). Ejemplo con Node.js:

```js
// make-admin.js  —  npm i firebase-admin
const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.cert(require('./serviceAccountKey.json')) });

(async () => {
  const uid = process.argv[2];                       // UID del usuario (Authentication → Users)
  await admin.auth().setCustomUserClaims(uid, { roles: ['ADMIN'] });
  console.log(`${uid} ahora es ADMIN`);
})();
```

```bash
node make-admin.js <UID>
```

- El repo incluye `addAdmin.js` (`node addAdmin.js [email]`), que busca el UID por email y asigna `{ ADMIN: true }`;
  la clave debe estar en `charme-et-chic-firebase-admin-key.json` (ignorada por git).
- `serviceAccountKey.json` se descarga en *Project settings* → *Service accounts* → *Generate new private key*.
  **No lo subas a git** (el `.gitignore` ya lo excluye) ni lo pongas en el frontend.
- Los claims se incorporan al token **la siguiente vez que se renueva**: el usuario debe cerrar sesión y volver a entrar, o
  el frontend llamar a `getIdToken(true)`.
- Alternativa sin código: guardar los roles en Firestore y leerlos desde la API exigiría el Admin SDK en el backend;
  no está implementado porque los custom claims viajan en el token y evitan una consulta por petición.

### Probar sin Firebase (modo local)

Solo para desarrollo. Valida tokens con un secreto compartido (HS256) en lugar de Firebase:

```bash
# Linux / macOS / Git Bash
export JWT_MODE=local
export JWT_LOCAL_SECRET="un-secreto-de-al-menos-32-caracteres!!"
mvn spring-boot:run
```

```powershell
# PowerShell
$env:JWT_MODE = "local"
$env:JWT_LOCAL_SECRET = "un-secreto-de-al-menos-32-caracteres!!"
mvn spring-boot:run
```

Genera un token en https://jwt.io (algoritmo `HS256`, el mismo secreto) con un payload como este:

```json
{ "sub": "usuario-1", "email": "ana@charme.cl", "name": "Ana", "roles": ["ADMIN"], "exp": 1893456000 }
```

(`exp` es un timestamp Unix en segundos; usa una fecha futura. En este modo no se comprueban `iss`/`aud`.) Este modo
**no debe usarse en producción**: la API lo avisa con un `WARN` al arrancar. El *Firebase Auth Emulator* no sirve para
este fin porque emite tokens sin firmar, que la API rechaza por diseño.

---

## Variables de entorno

| Variable | Por defecto | Descripción |
|---|---|---|
| `FIREBASE_PROJECT_ID` | `charme-et-chic` | **Project ID** de Firebase. Define el `iss` y el `aud` que se exigen |
| `JWT_MODE` | `firebase` | `firebase` (producción) o `local` (solo desarrollo) |
| `JWT_LOCAL_SECRET` | *(vacío)* | Secreto HMAC ≥ 32 caracteres; obligatorio si `JWT_MODE=local` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Orígenes permitidos, separados por coma |
| `ORDER_TAX_RATE` | `0.19` | Tasa de IVA aplicada al subtotal de las órdenes |
| `LOW_STOCK_THRESHOLD` | `5` | Umbral de `GET /api/products/low-stock` (stock **menor** a este valor) |
| `RABBITMQ_ADDRESSES` | `localhost:5672` | Nodos de RabbitMQ, separados por coma (`host1:5672,host2:5672` en clúster) |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Credenciales de RabbitMQ (los valores por defecto son solo para desarrollo local) |
| `RABBITMQ_VHOST` | `/` | Virtual host de RabbitMQ |
| `MESSAGING_ENABLED` | `true` | `false` = no publicar, no conectarse al arrancar y no iniciar consumidores |
| `RABBITMQ_RETRY_MAX_ATTEMPTS` | `3` | Intentos totales ante un error transitorio antes de mandar el mensaje a la DLQ (1–10) |
| `RABBITMQ_RETRY_BACKOFF_MS` | `1000` | Espera antes de cada reintento (se multiplica por el nº de intento) |
| `MESSAGING_SIMULATED_FAILURES` | `true` | Permite `simularError` en `POST /api/reports/inventory/async` (demo). Apagar en producción |

El resto de la configuración está en `src/main/resources/application.yml`.

---

## Mensajería (RabbitMQ)

Los nombres de exchanges, colas y routing keys están **solo** en `application.yml` (`app.messaging`) y se
declaran como beans en `messaging/config/RabbitMQConfig.java`. La API crea la topología sola al conectarse.

> Para administrar colas, exchanges y bindings por API REST está el microservicio aparte
> [`rabbit-admin/`](rabbit-admin/README.md) (puerto 8081).

| Cola | Exchange (tipo) | Routing key | Patrón | DLQ |
|---|---|---|---|---|
| `inventario.stock-bajo` | `charmeetchic.inventario` (direct) | `inventario.stock-bajo` | asíncrono | `inventario.stock-bajo.dlq` |
| `inventario.movimientos` | `charmeetchic.inventario` (direct) | `inventario.movimientos` | asíncrono | `inventario.movimientos.dlq` |
| `inventario.consulta-stock` | `charmeetchic.inventario` (direct) | `inventario.consulta-stock` | **síncrono (RPC)** | — (TTL = timeout) |
| `notificaciones.reportes` | `charmeetchic.notificaciones` (topic) | `notificacion.reporte.*` | asíncrono | `notificaciones.reportes.dlq` |
| `ventas.auditoria` | `charmeetchic.ventas` (**fanout**) | — | publish/subscribe | `ventas.auditoria.dlq` |
| `ventas.metricas` | `charmeetchic.ventas` (**fanout**) | — | publish/subscribe | `ventas.metricas.dlq` |

Los mensajes rechazados (NACK sin reencolar), vencidos (TTL) o descartados por cola llena van al DLX
`charmeetchic.dlx`, que los deja en la DLQ de su cola.

```bash
docker compose up -d            # RabbitMQ local (1 nodo) + consola en http://localhost:15672 (guest/guest)
mvn spring-boot:run             # al arrancar, el log dice "Conectado a RabbitMQ ... topología declarada"
```

La API **no depende** de que RabbitMQ esté arriba para arrancar, y `mvn verify` no lo necesita
(`src/test/resources/config/application.yml` desactiva la conexión en los tests).

### Qué publica cada operación

| Operación | Cola | Cuándo |
|---|---|---|
| Crear orden / cancelar orden / editar stock / crear producto | `inventario.movimientos` | Siempre que cambia el stock |
| Las mismas | `inventario.stock-bajo` | Solo si el stock **cruza** el umbral (antes ≥ 5, después < 5) |
| `POST /api/reports/inventory/async` | `notificaciones.reportes` | Una vez por solicitud |
| Crear orden | `charmeetchic.ventas` (fanout) → `ventas.auditoria` y `ventas.metricas` | **Un** evento; RabbitMQ entrega una copia a cada cola |
| `GET /api/inventory/stock-check/{sku}` | `inventario.consulta-stock` | Espera la respuesta (RPC) |

Los mensajes se publican **después del commit**: si la orden falla (p. ej. 409 por stock insuficiente), no
sale ningún mensaje y la auditoría no registra movimientos falsos.

### Síncrono vs. asíncrono

| | Síncrono — `GET /api/inventory/stock-check/{sku}` | Asíncrono — `POST /api/reports/inventory/async` |
|---|---|---|
| Qué hace el cliente HTTP | **Espera** la respuesta del consumidor | Recibe **202 + id** al instante y consulta después |
| Cómo vuelve la respuesta | `convertSendAndReceive` con **direct reply-to** (`reply_to` + `correlation_id`) | Se guarda en BD; `GET /api/reports/async/{id}` |
| Si el consumidor no está | **504** a los `RABBITMQ_RPC_TIMEOUT_MS` (3 s) | El mensaje espera en la cola; se procesa al volver |
| Cuándo conviene | Respuesta corta y que se necesita ya | Trabajo lento, reintentable o que no debe bloquear |

### Publish/subscribe (fanout)

Al crear una orden se publica **un** `SaleCompletedEvent` en `charmeetchic.ventas`. El exchange fanout ignora la
routing key y entrega una copia a **cada** cola unida: `ventas.auditoria` la guarda en BD
(`GET /api/reports/sales-audit`) y `ventas.metricas` actualiza un resumen en memoria
(`GET /api/reports/sales-metrics`). Son independientes: si uno se detiene, el otro sigue; sumar un tercer
suscriptor es solo unir otra cola al exchange.

### Retención

| Cola | `x-max-length` | `x-message-ttl` | Por qué |
|---|---|---|---|
| `inventario.stock-bajo` | 1.000 | 24 h | Una alerta de reposición vieja ya no sirve: vence y queda en la DLQ |
| `inventario.movimientos` | 10.000 | — | Auditoría: nunca debe vencer |
| `notificaciones.reportes` | 500 | — | — |
| `inventario.consulta-stock` | 1.000 | 3 s (= timeout RPC) | Pasado el timeout nadie espera la respuesta |
| `ventas.auditoria` / `ventas.metricas` | 10.000 | — | — |
| **todas las DLQ** | 10.000 | **7 días** | Revisar fallos sin que crezcan sin límite |

**Overflow `drop-head`** (descarta el más antiguo): como las colas tienen DLX, el mensaje descartado va a la DLQ
con motivo `maxlen`, nada se pierde en silencio. Con `reject-publish` se rechazaría el mensaje NUEVO y, como el
productor no usa *publisher confirms*, se perdería sin rastro.

**Argumentos de cola vs. policy del broker.** Se aplican como **argumentos** declarados en `RabbitMQConfig`
(valores en `app.messaging.retention` y en cada cola del yml): quedan versionados con el código, se ven en la
consola y se prueban en `RabbitMQConfigTest`. La contra: RabbitMQ no permite cambiar los argumentos de una cola
existente (`PRECONDITION_FAILED - inequivalent arg ...`). Para cambiarlos:

```bash
./deploy/recrear-colas.sh        # borra las colas de la app (¡y sus mensajes!); pide confirmación
# y reiniciar la API para que las vuelva a declarar
```

Una *policy* (`rabbitmqctl set_policy`) sí se puede cambiar en caliente, pero los valores quedarían fuera del
código y del yml. Como la VM de Azure se crea desde cero, los argumentos no generan conflicto allí.

### Alertas operacionales

`rabbit-admin` revisa RabbitMQ cada 30 s (`AlertMonitor`, `@Scheduled`) por la API de management y deja
`ALERTA` en su log cuando aparece un problema (y `ALERTA RESUELTA` cuando se va). `GET /admin/alerts` (por el
gateway) muestra el estado; `?refresh=true` revisa en el momento.

| Alerta | Severidad |
|---|---|
| DLQ con mensajes | CRITICA |
| Cola principal llena / sobre el 80 % de su `x-max-length` | CRITICA / ADVERTENCIA |
| Cola con mensajes y sin consumidores | ADVERTENCIA |
| Nodo del clúster caído | CRITICA |
| Alarma de memoria o de disco de RabbitMQ | CRITICA |
| API de management inalcanzable | CRITICA |

Para provocar la alarma de disco en la demo (también se ve en rojo en la consola, Overview → Nodes):
`docker exec <contenedor-rabbitmq> rabbitmqctl set_disk_free_limit 100000GB` y para volver:
`rabbitmqctl set_disk_free_limit 1GB`. Mientras dure, RabbitMQ **bloquea a los productores**: no crear órdenes.

### Durabilidad (procedimiento de demo)

Las colas y exchanges son **durables** y los mensajes se publican **persistentes** (`delivery_mode=2`): sobreviven
a un reinicio del broker. Para mostrarlo (con token ADMIN):

```bash
API=https://<DOMAIN>/api; AUTH="Authorization: Bearer $TOKEN"
curl -H "$AUTH" $API/messaging/consumers                                          # ids y estado
curl -H "$AUTH" -X POST $API/messaging/consumers/consumidor-movimientos/pause     # 1. pausar
curl -H "$AUTH" -H "Content-Type: application/json" -X POST $API/orders \
     -d '{"shippingAddress":"Demo","items":[{"productId":1,"quantity":1}]}'       # 2. publicar
#   consola: inventario.movimientos tiene 1 mensaje "Ready" y 0 consumidores; /admin/alerts lo avisa
docker compose -f docker-compose.prod.yml restart rabbitmq1                       # 3. reiniciar el broker
#   consola: el mensaje SIGUE ahí
curl -H "$AUTH" -X POST $API/messaging/consumers/consumidor-movimientos/resume    # 4. reanudar
#   log del backend: "Auditoría: VENTA ..." y "ACK (procesado)"; la cola queda en 0
```

El control de consumidores está en la API de inventario y no en `rabbit-admin` porque los consumidores son de
ese proceso: RabbitMQ no tiene una operación para "pausar" el consumidor de otra aplicación.

### ACK manual, reintentos y DLQ

Todos los consumidores (`messaging/consumer/inventario`, `messaging/consumer/notificaciones`) usan
`ManualAckProcessor`:

| Situación | Decisión | Log |
|---|---|---|
| Procesado sin error | `basicAck` | `intento 1/3 -> ACK (procesado)` |
| JSON ilegible, no pasa Bean Validation o el registro no existe | `basicNack(requeue=false)` → DLQ, **sin reintentar** | `NACK a DLQ (mensaje inválido, no se reintenta)` |
| Error transitorio (BD caída, timeout...) e intento < 3 | Espera, republica una copia con `x-retry-count + 1` y hace ACK del original | `falló por error transitorio` + `republicado como intento 2/3` |
| Error transitorio en el intento 3 | `basicNack(requeue=false)` → DLQ | `NACK a DLQ (reintentos agotados)` |

Nunca se usa `basicNack(requeue=true)`: reencola sin contador y genera un loop infinito.

### Demo: provocar reintentos y DLQ

Requiere un token ADMIN. Para probar sin Firebase, levanta la API en modo local (ver
[Probar sin Firebase](#probar-sin-firebase-modo-local)) y usa un token HS256 con `"roles":["ADMIN","USER"]`.

```bash
API=http://localhost:8080/api; AUTH="Authorization: Bearer $TOKEN"

# 1) Reporte normal -> COMPLETADO
curl -i -H "$AUTH" -X POST $API/reports/inventory/async

# 2) Falla una vez y el reintento lo procesa -> COMPLETADO (log: intento 1/3 falló... intento 2/3 -> ACK)
curl -H "$AUTH" -H "Content-Type: application/json" -X POST $API/reports/inventory/async -d '{"simularError":"TRANSITORIO_UNA_VEZ"}'

# 3) Falla siempre -> 3 intentos -> DLQ -> FALLIDO
curl -H "$AUTH" -H "Content-Type: application/json" -X POST $API/reports/inventory/async -d '{"simularError":"TRANSITORIO"}'

# 4) Mensaje inválido -> DLQ directo, sin reintentos -> FALLIDO
curl -H "$AUTH" -H "Content-Type: application/json" -X POST $API/reports/inventory/async -d '{"simularError":"INVALIDO"}'

# Estado de cualquiera de ellos (el id viene en la respuesta y en el header Location)
curl -H "$AUTH" $API/reports/async/<id>
```

Luego, en la consola (http://localhost:15672 → Queues), `notificaciones.reportes.dlq` tiene los mensajes
fallidos. En "Get messages" se ven el header `x-death` (motivo `rejected`) y `x-retry-count`.

**Mensaje basura en una cola real:** en la consola, Exchanges → `charmeetchic.inventario` → Publish message,
routing key `inventario.movimientos`, payload `esto no es json`. El log muestra
`NACK a DLQ (mensaje inválido...)` y el mensaje aparece en `inventario.movimientos.dlq`.

**Alerta de stock bajo:** compra 4 unidades del "Estuche Joyero Viaje" (`ACC-EST-003`, stock 8). El log
muestra `ALERTA STOCK BAJO`. Una segunda compra del mismo producto solo genera el movimiento, sin otra alerta.

---

## Producción (Azure)

El repo tiene tres apps Spring Boot independientes y un compose que las levanta en una sola VM:

| Carpeta | App | Puerto interno |
|---|---|---|
| `/` | API de inventario (este README) | 8080 |
| [`rabbit-admin/`](rabbit-admin/README.md) | Administrador de RabbitMQ | 8081 |
| [`gateway/`](gateway/README.md) | API Gateway (valida JWT, CORS, rutas) | 8000 |

`docker-compose.prod.yml` agrega RabbitMQ en clúster de 2 nodos, PostgreSQL y Caddy (HTTPS con Let's Encrypt).
Guía paso a paso (crear la VM, NSG, Docker, `.env`, verificar el clúster): **[deploy/README.md](deploy/README.md)**.

Perfil `prod` del backend (`application-prod.yml`): PostgreSQL por variables de entorno (`DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`), `ddl-auto: update` (no borra datos al reiniciar), catálogo inicial idempotente
(`data-prod.sql`) y `forward-headers-strategy` para generar URLs `https://` detrás del proxy.

---

## Conectar con el frontend (Angular)

La API acepta peticiones desde `http://localhost:4200` (CORS). Para otro origen, define `CORS_ALLOWED_ORIGINS`.

**1. Configuración** (`environment.ts`):

```ts
export const environment = {
  apiUrl: 'http://localhost:8080/api',
  firebase: {                       // Firebase Console → Project settings → Your apps → Web
    apiKey: '...',
    authDomain: 'charmeetchic-a1b2c.firebaseapp.com',
    projectId: 'charmeetchic-a1b2c',   // ← el MISMO valor que FIREBASE_PROJECT_ID en el backend
    appId: '...',
  },
};
```

**2. Login** (con `@angular/fire`, API modular):

```ts
import { Auth, signInWithEmailAndPassword, GoogleAuthProvider, signInWithPopup } from '@angular/fire/auth';

await signInWithEmailAndPassword(this.auth, email, password);
// o: await signInWithPopup(this.auth, new GoogleAuthProvider());
```

**3. Enviar el ID token en cada petición a la API** (interceptor):

```ts
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Auth } from '@angular/fire/auth';
import { from, switchMap } from 'rxjs';
import { environment } from '../environments/environment';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(Auth).currentUser;
  if (!user || !req.url.startsWith(environment.apiUrl)) return next(req);

  // getIdToken() devuelve el token en caché y lo renueva solo cuando está por expirar
  return from(user.getIdToken()).pipe(
    switchMap(token => next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
  );
};
```

Regístralo con `provideHttpClient(withInterceptors([authInterceptor]))`. Los endpoints públicos funcionan con o sin token.

**4. Consumir** (paginación de Spring Data):

```ts
this.http.get<Page<Product>>(`${environment.apiUrl}/products`, {
  params: { page: 0, size: 12, sort: 'name,asc' },
});
```

**5. Saber si el usuario es admin:** llamar a `GET /api/profile` y comprobar `roles.includes('ADMIN')`, o leer los
claims del token con `(await user.getIdTokenResult()).claims['roles']`. La API valida los permisos igualmente en el servidor.

---

## Estructura del proyecto

```
src/main/java/com/charmeetchic/api/
├── CharmeetchicApiApplication.java
├── config/       SecurityConfig · CorsConfig · JwtConfig
├── controller/   Product · Category · Order · Report · Health · Profile
├── dto/          ProductDTO · CategoryDTO · OrderDTO · OrderItemDTO · OrderRequestDTO · OrderStatusDTO
│                 CartItemDTO · ReportDTO · UserDTO
├── entity/       Product · Category · Order · OrderItem · User
├── enums/        OrderStatus · Role
├── exception/    GlobalExceptionHandler · ApiError · InvalidJwt · ResourceNotFound · InsufficientStock
│                 Validation · Conflict
├── filter/       JwtValidationFilter
├── repository/   Product · Category · Order · OrderItem · User
├── security/     AuthenticatedUser (principal del JWT)
└── service/      Product · Category · Order · Report · User · JwtService · JwksKeyProvider
src/main/resources/  application.yml · data.sql
src/test/java/...    tests de servicios (Mockito) y de integración (MockMvc + H2)
```

Notas sobre la estructura:

- El paquete se llama `enums` y no `enum`, porque `enum` es palabra reservada de Java.
- **Dónde vive cada cosa de la autenticación:** `JwtValidationFilter` lee la cabecera y crea la autenticación de Spring
  Security; `JwtService` verifica el token (firma, `iss`, `aud`, `sub`, `exp`); `JwksKeyProvider` descarga y cachea las
  claves públicas de Google; `JwtConfig` lee `firebase.project-id`. `SecurityConfig` define qué rol necesita cada ruta.
- El filtro **no es un `@Component`** a propósito: si lo fuera, Spring Boot lo registraría también como filtro de
  servlet global, ejecutándose fuera de la cadena de seguridad. `SecurityConfig` lo instancia e inserta solo en ella.
- No hay `CorsFilter.java`: la política CORS vive en `CorsConfig` y Spring Security la aplica *antes* de la
  autenticación (necesario para los preflight `OPTIONS`).
- Las rutas de los controladores son `/products`, `/orders`... (sin `/api`) porque el prefijo `/api` ya lo aporta
  `server.servlet.context-path`. Si además se escribiera en los controladores, las URLs serían `/api/api/...`.
- Los datos iniciales están en `data.sql` (no `schema.sql`): con `ddl-auto: create-drop` Hibernate crea las tablas
  y un `schema.sql` con `INSERT` se ejecutaría antes de que existan.

---

## Tests

```bash
mvn test
```

- **Servicios** (`ProductService`, `CategoryService`, `OrderService`, `ReportService`): Mockito, sin base de datos.
- **`JwtService`**: en modo Firebase, con un par RSA generado en el test que hace de "Google" y la descarga de claves
  simulada (`JwksKeyProvider` mockeado). Cubre emisor/audiencia incorrectos, token de otro proyecto, `sub` ausente,
  expirado, `iat`/`auth_time` futuros, firma falsificada, payload manipulado, `kid` desconocido y el ataque de confusión
  HS256 vs RS256; y la lectura de roles desde los tres formatos de custom claims.
- **Integración** (`FirebaseAuthIntegrationTest`): la cadena de seguridad completa en modo Firebase con tokens RS256
  (usuario normal, admin por custom claim, token falsificado, sin firma, HS256, de otro proyecto, expirado).
- **Integración** (`ApiIntegrationTest`, tokens locales HS256): matriz de acceso por rol, flujo completo de órdenes
  (stock, propiedad, estados, cancelación), reportes, validaciones, formato de errores y CORS.

---

## Migración desde Azure AD

Esta API validaba antes tokens de Azure AD. Qué cambió:

| | Azure AD | Firebase |
|---|---|---|
| Configuración | `AZURE_TENANT_ID` + `AZURE_CLIENT_ID` | `FIREBASE_PROJECT_ID` |
| `iss` | `https://login.microsoftonline.com/<tenant>/v2.0` (o `sts.windows.net`) | `https://securetoken.google.com/<project>` |
| `aud` | `<client-id>` / `api://<client-id>` | `<project>` |
| Id de usuario | claim `oid` | claim `sub` (UID de Firebase) |
| Roles | *App roles* (claim `roles`) | *Custom claims* (`roles` / `role` / `admin` / `ADMIN`) |
| Claves de firma | JWKS del tenant de Microsoft | JWKS de `securetoken@system.gserviceaccount.com` |

Las entidades, repositorios, servicios de negocio, controladores y reglas de acceso no cambiaron. Los `userId` guardados
ahora son UIDs de Firebase; con la base H2 en memoria no hay datos previos que migrar (en una base persistente, los
`userId` antiguos —`oid` de Azure— no coincidirían con los nuevos UIDs).

---

## Evolución hacia ecommerce / microservicios

La API está pensada para separarse por dominios sin reescribir la seguridad:

- **Identidad compartida:** el UID de Firebase es el `userId` de las órdenes. Un servicio nuevo (Cart, Checkout,
  Payment) solo necesita el mismo `JwtService` + `SecurityConfig` para validar los mismos tokens; no hace falta
  compartir una base de datos de usuarios.
- **Límites de módulo:** `catalog` (Product/Category), `orders` (Order/OrderItem), `reports` y `users` ya
  interactúan solo a través de servicios y DTOs, por lo que pueden extraerse a servicios independientes.
- **Carrito:** `CartItemDTO` ya modela la línea de carrito; un servicio Cart puede persistirlo y llamar a
  `POST /orders` en el checkout.
- **Pagos:** basta añadir estados a `OrderStatus` (p. ej. `PAID`) y un servicio Payment que confirme la orden.

### Antes de producción

- Sustituir H2 por una base de datos real (PostgreSQL/SQL Server), `ddl-auto: validate` y migraciones con Flyway/Liquibase.
- Desactivar la consola H2 (`spring.h2.console.enabled=false`) y **no** usar `JWT_MODE=local`.
- Verificar que `FIREBASE_PROJECT_ID` (por defecto `charme-et-chic`) coincide con el Project ID del proyecto de Firebase usado por el frontend.
- Servir la API solo por HTTPS y ajustar `CORS_ALLOWED_ORIGINS` al dominio real del frontend.
- Restringir `logging.level.com.charmeetchic` a `INFO`.
