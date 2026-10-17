# charmeetchic-rabbit-admin

Microservicio que administra RabbitMQ por API REST: crear, listar y borrar **colas**, **exchanges** y
**bindings**. Es una app Spring Boot **separada** de la API de inventario: tiene su propio `pom.xml`, su
propio `Dockerfile`, su propio puerto (8081) y en `docker-compose` corre como otro contenedor.

## Cómo ejecutar

```bash
docker compose up -d          # desde la raíz del repo: RabbitMQ local
cd rabbit-admin
mvn spring-boot:run           # http://localhost:8081
mvn verify                    # tests (no necesitan RabbitMQ)
```

## Endpoints

Todos exigen `Authorization: Bearer <ID token de Firebase>` de un usuario **ADMIN**, salvo `GET /health`.

| Método | Ruta | Resultado |
|---|---|---|
| GET | `/health` | Público. Estado del servicio |
| GET | `/queues` | Colas con mensajes, consumidores y `system` (protegida o no) |
| POST | `/queues` | `201` creada · `200` ya existía igual · `409` existe con otra config · `404` DLX inexistente |
| DELETE | `/queues/{name}?ifEmpty=&ifUnused=` | `204` · `404` no existe · `409` tiene mensajes/consumidores |
| GET | `/exchanges` | Exchanges (sin el por defecto ni los `amq.*`) |
| POST | `/exchanges` | `201` · `200` · `409` (p. ej. mismo nombre, otro tipo) |
| DELETE | `/exchanges/{name}` | `204` · `404` |
| GET | `/bindings` | Bindings exchange → cola |
| POST | `/bindings` | `201` · `200` · `404` si la cola o el exchange no existen |
| DELETE | `/bindings?exchange=&queue=&routingKey=` | `204` · `404` |

Comunes a todos: `400` datos inválidos (con `details` por campo) · `401` token ausente, inválido o vencido
· `403` sin rol ADMIN **o** recurso del sistema · `503` RabbitMQ no disponible.

```bash
A="Authorization: Bearer $TOKEN"; J="Content-Type: application/json"; U=http://localhost:8081
curl -H "$A" -H "$J" -X POST $U/exchanges -d '{"name":"demo.pedidos","type":"topic"}'
curl -H "$A" -H "$J" -X POST $U/queues    -d '{"name":"demo.pedidos.web","messageTtlMs":60000}'
curl -H "$A" -H "$J" -X POST $U/bindings  -d '{"exchange":"demo.pedidos","queue":"demo.pedidos.web","routingKey":"pedido.*"}'
curl -H "$A" -X DELETE "$U/bindings?exchange=demo.pedidos&queue=demo.pedidos.web&routingKey=pedido.*"
curl -H "$A" -X DELETE $U/queues/inventario.stock-bajo      # 403: es del sistema
```

### Validaciones (Bean Validation en los DTOs)

- **Nombres**: obligatorios, máximo 255, solo letras, números, `.` `_` `-` `:`, empiezan con letra o número,
  y no pueden empezar con `amq.` (reservado por RabbitMQ).
- **Tipo de exchange**: `direct`, `topic`, `fanout` o `headers` (sin distinguir mayúsculas).
- **Routing key**: puede ser vacía (fanout); admite los comodines `*` y `#`.
- **Cola**: `deadLetterRoutingKey` requiere `deadLetterExchange`; `messageTtlMs` y `maxLength` deben ser positivos.
- **Existencia** (la revisa el servicio, no Bean Validation, porque hay que preguntarle al broker): el binding
  debe apuntar a una cola y un exchange existentes, y el `deadLetterExchange` también debe existir.

### Recursos protegidos

Prefijos `charmeetchic.`, `inventario.` y `notificaciones.` (`ADMIN_PROTECTED_PREFIXES`): no se pueden
**crear ni borrar** colas o exchanges con esos nombres, ni bindings **hacia** una cola del sistema. Sí se
permite unir un exchange del sistema a una cola propia: por ejemplo, `charmeetchic.inventario` →
`auditoria.copia` con `inventario.movimientos` recibe una copia de cada movimiento sin afectar a la API.

## Diseño

```
controller/  QueueController, ExchangeController, BindingController  -> solo DTOs y códigos HTTP
service/     RabbitAdminService                                     -> TODA la lógica y las reglas
client/      RabbitManagementClient                                 -> lecturas por la API HTTP (:15672)
             AmqpAdmin (RabbitAdmin de Spring)                      -> escrituras por AMQP (:5672)
```

AMQP sirve para declarar y borrar, pero no tiene operaciones para **listar** ni para **leer la configuración**
de un exchange: para eso se usa la API HTTP del plugin de administración (la misma que usa la consola web).

## Seguridad: OAuth2 Resource Server

Se usa `spring-boot-starter-oauth2-resource-server`: Spring Security valida el token solo, sin un filtro
propio. `SecurityConfig` define el `JwtDecoder` con:

1. Firma **RS256** verificada con las claves públicas de Google (JWKS, se descargan con el primer token).
2. `exp` vigente, `iss = https://securetoken.google.com/charme-et-chic`, `aud` contiene `charme-et-chic`,
   `sub` presente (`FirebaseTokenValidators`).
3. Roles desde los custom claims (`FirebaseRolesConverter`): `{"ADMIN": true}` (el de `addAdmin.js`),
   `{"admin": true}`, `{"role": "ADMIN"}` o `{"roles": ["ADMIN"]}`, igual que la API de inventario.

| Variable | Por defecto | Descripción |
|---|---|---|
| `PORT` | `8081` | Puerto HTTP |
| `RABBITMQ_ADDRESSES` | `localhost:5672` | Nodos AMQP (separados por coma en clúster) |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Credenciales (AMQP y HTTP); solo desarrollo |
| `RABBITMQ_VHOST` | `/` | Virtual host |
| `RABBITMQ_MANAGEMENT_URL` | `http://localhost:15672` | API HTTP de administración |
| `ADMIN_PROTECTED_PREFIXES` | `charmeetchic.,inventario.,notificaciones.` | Prefijos protegidos |
| `FIREBASE_PROJECT_ID` | `charme-et-chic` | Define el issuer y la audience exigidos |
