# charmeetchic-kafka-admin

Microservicio **administrador de Kafka** (EP5). Aplicación Spring Boot separada de la API de inventario (otro
proceso, contenedor y puerto: **8082**), con la misma estructura y seguridad que `rabbit-admin/`.

- Toda operación pasa por `KafkaAdminService`, la única clase que usa el `AdminClient` de Kafka.
- Los controladores reciben DTOs validados con Bean Validation y devuelven DTOs (nunca clases de Kafka).
- Seguridad: cada petición trae el **ID token de Firebase** (`Authorization: Bearer ...`), validado como OAuth2
  Resource Server (firma RS256, `iss`, `aud`, `exp`, `sub`) y exige rol **ADMIN** (custom claim `{"ADMIN": true}`).
- Por el gateway: `https://<gateway>/kafka-admin/**` (el gateway quita el prefijo).

## Endpoints

| Método | Ruta | Qué hace | Errores |
|---|---|---|---|
| GET | `/health` | Healthcheck (público) | |
| GET | `/topics[?internos=true]` | Lista tópicos (particiones, RF, protegido) | 503 |
| GET | `/topics/{name}` | Particiones con **líder, réplicas e ISR**, y configs | 404 |
| POST | `/topics` | Crea `{name, partitions, replicationFactor, configs}` | 400, 409 |
| PATCH | `/topics/{name}/configs` | Cambia configs `{configs: {"retention.ms": "60000"}}` | 400, 403, 404 |
| POST | `/topics/{name}/partitions` | Aumenta a `{totalPartitions}` (Kafka no permite reducir) | 400, 404 |
| DELETE | `/topics/{name}` | Borra el tópico | 403 (protegido), 404 |
| GET | `/consumer-groups` | Lista los grupos y su estado | |
| GET | `/consumer-groups/{id}` | Miembros, offset confirmado, fin del log y **LAG por partición** | 404 |
| POST | `/consumer-groups/{id}/reset-offsets` | `{topic, strategy: EARLIEST\|LATEST\|OFFSET, offset?, partition?}` | 400, 409 (grupo activo) |

**Configs permitidas** (lista blanca): `cleanup.policy`, `retention.ms`, `retention.bytes`, `min.insync.replicas`,
`segment.ms`, `min.compaction.lag.ms`, `delete.retention.ms`, `min.cleanable.dirty.ratio`, `max.message.bytes`,
`compression.type`. Se validan los valores (números enteros, `delete|compact|compact,delete`...).

**Protecciones**

- Tópicos de la API (`ADMIN_PROTECTED_TOPICS`, por defecto `ventas.eventos`, `inventario.stock` y sus `.DLT`): se
  pueden describir y ajustar, pero **no borrar** (403).
- Tópicos internos de Kafka (prefijo `__`, p. ej. `__consumer_offsets`): no se pueden crear, modificar ni borrar.
- Reset de offsets: Kafka solo lo permite con el grupo **vacío**. Pausar un listener no basta (sigue siendo miembro
  del grupo): hay que detenerlo (`POST /api/streaming/consumers/{id}/stop` en la API) → 409 si no.

## Variables de entorno

| Variable | Por defecto | |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092,localhost:29092,localhost:39092` | En Docker: `kafka1:9092,kafka2:9092,kafka3:9092` |
| `KAFKA_ADMIN_TIMEOUT_MS` | `15000` | Espera máxima de cada operación (luego 503) |
| `ADMIN_PROTECTED_TOPICS` | tópicos de la API | Separados por coma |
| `FIREBASE_PROJECT_ID` | `charme-et-chic` | |
| `PORT` | `8082` | |

## Tests

```bash
cd kafka-admin && mvn verify
```

- `KafkaAdminServiceTest`: el servicio contra un **broker Kafka embebido real** (crear, modificar, aumentar
  particiones, borrar, protecciones, lag con un consumidor real, reset con grupo activo y detenido).
- `KafkaAdminApiIntegrationTest`: seguridad (401/403 con tokens RS256 firmados en el test), validación de DTOs y
  traducción de errores a 400/403/404/409/503, con el servicio simulado.
