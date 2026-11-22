# Guía de contribución — charmeetchic-api

Este documento describe cómo se trabaja en el repositorio: modelo de ramas, nombres, commits, merges, revisión y
estructura del proyecto. El diagrama del flujo y del pipeline de CI está en [docs/workflow.md](docs/workflow.md).
Volver al [README](README.md).

> **Proyecto individual.** Todo el código y la revisión los realiza una sola persona. Las reglas de este documento se
> aplican igual que en un equipo, para mantener la trazabilidad y la calidad del historial.

---

## Modelo de ramas (GitFlow)

| Rama | Propósito | Se crea desde | Se mergea hacia |
|---|---|---|---|
| `main` | Código en producción. Cada merge corresponde a una versión publicada con tag `vX.Y.Z`. | — (rama permanente) | — |
| `develop` | Rama de integración: contiene las funcionalidades terminadas que irán en la próxima release. | `main` (una sola vez) | `main` (como release) |
| `feature/*` | Desarrollo de una funcionalidad nueva o mejora. | `develop` | `develop` |
| `hotfix/*` | Corrección urgente sobre `main` (producción): puede afectar código, configuración o documentación. | `main` | `main` (con tag) y luego `develop` (back-merge) |
| `docs/*` | Cambios solo de documentación. | `develop` | `develop` |

`main` y `develop` son ramas permanentes y protegidas: no se hace push directo a ellas, todo entra mediante PR.

---

## Naming de ramas

- Formato: `prefijo/descripcion-corta` en **kebab-case** (minúsculas, palabras separadas por guiones, sin tildes ni
  espacios).
- Prefijos permitidos: `feature/`, `hotfix/`, `docs/`.
- La descripción indica *qué* cambia, no *quién* lo hace ni un número de tarea suelto.

Ejemplos reales de este repositorio:

| Rama | Qué contiene |
|---|---|
| `feature/health-version` | Expone la versión de la API en `GET /api/health`. |
| `feature/filtro-precio` | Filtro `minPrice`/`maxPrice` en `GET /api/products/search`. |
| `hotfix/firebase-project-id` | Corrige el Project ID de Firebase por defecto documentado en el README de producción. |
| `docs/guia-devops` | Esta guía de contribución y la documentación del flujo DevOps. |

---

## Convención de commits

Se usa [Conventional Commits](https://www.conventionalcommits.org/es/v1.0.0/):

```
tipo(scope): descripción
```

- **descripción** en español, en **modo imperativo / presente** ("agrega", "corrige", "documenta"), en minúsculas y
  sin punto final.
- **scope** opcional: indica el área afectada.

### Tipos

| Tipo | Cuándo usarlo |
|---|---|
| `feat` | Nueva funcionalidad para el usuario de la API. |
| `fix` | Corrección de un error. |
| `docs` | Solo documentación (README, guías, comentarios de documentación). |
| `test` | Agregar o corregir tests, sin cambiar código de producción. |
| `ci` | Cambios en el pipeline de integración continua (`.github/workflows`). |
| `build` | Cambios en el sistema de build (`pom.xml`: plugins de Maven, ejecución de pruebas, cobertura). |
| `chore` | Tareas de mantenimiento que no afectan el código de la aplicación (dependencias, configuración del repo). |
| `refactor` | Cambio de código que no agrega funcionalidad ni corrige errores. |

### Scopes usados

| Scope | Área |
|---|---|
| `health` | Endpoint de salud `/api/health`. |
| `products` | Catálogo de productos y búsqueda. |
| `auth` | Autenticación y autorización con Firebase (JWT, roles). |
| `config` | Configuración de Spring (`application.yml`, CORS, seguridad). |
| `readme` | Documentación en `README.md`. |
| `docker` | Contenerización: `Dockerfile`, `.dockerignore` y el job `docker-build` del CI. |
| `tests` | Configuración de las pruebas y la cobertura (surefire, failsafe, JaCoCo). Se usa con `build` y `ci`. |
| `orders` | Órdenes de compra: creación, stock, estados y cancelación. |
| `users` | Sincronización del usuario local con el token de Firebase. |
| `errors` | Manejo uniforme de errores (`GlobalExceptionHandler`, `ApiError`). |

### Ejemplos reales (`git log`)

```
feat(health): expone la versión de la API en /health
test(health): verifica campo version en respuesta de /health
docs(readme): documenta el campo version en la respuesta de /api/health
feat(products): agrega filtro de rango de precio a GET /products/search
test(products): cubre el filtro minPrice/maxPrice de /products/search
docs(readme): documenta minPrice/maxPrice en /api/products/search
fix(products): rechaza precios negativos en la búsqueda por rango
ci: agrega workflow de integración continua y plantilla de PR
docs(readme): corrige Project ID de Firebase por defecto en el README
```

El último ejemplo corresponde al hotfix `hotfix/firebase-project-id`: aunque es una corrección urgente sobre
`main`, lo que se corrige es documentación, por eso su tipo es `docs` y no `fix`.

Cada commit debe ser una unidad lógica: el código, sus tests y su documentación van en commits separados cuando eso
facilita la revisión (como en `feature/health-version` y `feature/filtro-precio`).

---

## Flujo de merge

### feature/* y docs/* → develop

1. Crear la rama desde `develop` actualizado.
2. Hacer commits siguiendo la convención.
3. Abrir un PR hacia `develop` usando la plantilla.
4. Esperar el check `build-and-test` en verde y resolver la revisión.
5. Mergear con **merge commit** (*Create a merge commit*), **no squash**: así se conservan los commits individuales
   (feat, test, docs, fix) y queda registrado en el historial qué rama entró y en qué PR.

### develop → main (release)

1. Abrir un PR de `develop` hacia `main` cuando el conjunto de funcionalidades está listo.
2. Tras el merge, crear un **tag anotado** sobre el commit de merge en `main`:

   ```bash
   git checkout main && git pull
   git tag -a v1.0.0 -m "Primera versión estable: health con versión y filtro por precio"
   git push origin v1.0.0
   ```

### hotfix/* → main → develop

`hotfix/*` es una corrección urgente sobre `main` (producción). No siempre es código: el tipo del commit depende de
lo que se corrige (`fix(...)` para código, `docs(...)` para documentación, etc.).

1. Crear la rama desde `main`: `git checkout -b hotfix/firebase-project-id main`.
2. Aplicar la corrección y abrir un PR hacia `main`. En este proyecto el hotfix corrige documentación, con el commit
   `docs(readme): corrige Project ID de Firebase por defecto en el README`.
3. Tras el merge, crear el tag anotado de la versión de parche (`v1.0.1`).
4. Hacer **back-merge** de `main` a `develop` (PR `main → develop`) para que la corrección no se pierda en la
   siguiente release.

### Versionado semántico

Las versiones siguen [SemVer](https://semver.org/lang/es/) con el formato `MAJOR.MINOR.PATCH`:

| Parte | Se incrementa cuando… |
|---|---|
| `MAJOR` | Hay cambios incompatibles en la API (un cliente existente dejaría de funcionar). |
| `MINOR` | Se agrega funcionalidad compatible con lo anterior. |
| `PATCH` | Se corrigen errores sin cambiar la API. |

En este proyecto:

- **`v1.0.0`**: primera release publicada en `main` mediante el PR #3 (`develop → main`), con la versión expuesta en
  `/api/health` y el filtro de precio en `/api/products/search` (PR #1 y PR #2 integrados en `develop`).
- **`v1.0.1`**: versión de parche generada por `hotfix/firebase-project-id`; corrige el Project ID de Firebase por
  defecto documentado en el README sin agregar funcionalidad ni cambiar la API, por eso solo cambia `PATCH`. Tras el
  merge a `main` se hace back-merge a `develop`.
- **`v1.1.0`**: release `develop → main` que lleva a producción la documentación de `docs/guia-devops` (esta guía,
  [docs/workflow.md](docs/workflow.md) y la sección DevOps del README). Es `MINOR` porque incorpora documentación y
  convenciones nuevas sin cambiar el contrato de la API: las rutas, los DTOs y la autenticación siguen iguales, por
  lo que ningún cliente existente se ve afectado.

La secuencia completa (release, hotfix con back-merge y nueva release) está diagramada en
[docs/workflow.md](docs/workflow.md).

---

## Estrategia de revisión

Reglas aplicadas a `main` y `develop` (protección de ramas en GitHub):

- **PR obligatorio**: no se permite push directo a `main` ni a `develop`.
- **Check requerido `build-and-test`**: el job del workflow [CI](.github/workflows/ci.yml) debe terminar en verde
  para poder mergear.
- **Revisión con comentarios en línea** sobre el diff del PR (pestaña *Files changed*).
- **Conversaciones resueltas** antes del merge: cada comentario de review se atiende con un commit o una respuesta y
  se marca como resuelto.
- **Plantilla de PR obligatoria** ([.github/pull_request_template.md](.github/pull_request_template.md)): descripción,
  tipo de cambio (feature, hotfix, docs, release), cambios realizados, cómo se probó y checklist.
- **No borrar las ramas tras el merge**, para conservar la evidencia del flujo GitFlow.

### Revisión en un proyecto individual

El proyecto lo desarrolla una sola persona y GitHub **no permite aprobar un PR propio**. Por eso no se exige una
aprobación formal; en su lugar, la revisión se documenta con **comentarios de review** en línea sobre el diff, que
deben quedar resueltos antes del merge.

**Ejemplo real — PR #2 (`feature/filtro-precio`):** durante la revisión se detectó que la búsqueda aceptaba valores
negativos en `minPrice`/`maxPrice`. Se dejó el comentario en la línea correspondiente, se corrigió con un commit
adicional en la misma rama (`fix(products): rechaza precios negativos en la búsqueda por rango`), el check
`build-and-test` volvió a pasar, se resolvió la conversación y recién entonces se mergeó a `develop`.

---

## Política de calidad

### Tipos de pruebas

| Tipo | Archivos | Plugin / fase | Qué prueban |
|---|---|---|---|
| Unitarias | Todo `*Test.java` que **no** termine en `IntegrationTest` (`service/*Test`, `filter/*Test`, `exception/*Test`, `HealthControllerTest`) | `maven-surefire-plugin`, fase `test` | Reglas de negocio de cada clase aislada con mocks (Mockito): stock, totales, estados de orden, validación de tokens, formato de errores. Rápidas, sin red. |
| Integración | `*IntegrationTest.java` (`ApiIntegrationTest`, `FirebaseAuthIntegrationTest`) | `maven-failsafe-plugin`, fases `integration-test` y `verify` | La API completa por HTTP: contexto de Spring, seguridad, roles, H2, serialización JSON y códigos de estado. |

```bash
mvn -B test     # solo unitarias (feedback rápido mientras se programa)
mvn -B verify   # unitarias + integración + JAR + reporte y umbrales de cobertura
```

`HealthControllerTest` levanta el contexto de Spring, pero por convención de nombre corre con las unitarias; es una
sola prueba rápida.

### Qué se ejecuta en cada step del CI (job `build-and-test`)

| Step | Comando | Si falla |
|---|---|---|
| Pruebas unitarias | `mvn -B test` | Se detiene el job: hay una regresión en la lógica. |
| Pruebas de integración y verificación de cobertura | `mvn -B verify -DskipUTs=true` | Falla si una prueba de integración falla **o** si la cobertura baja de los umbrales. Reutiliza lo compilado y no repite las unitarias. |
| Smoke test del JAR | `java -jar` + `curl /api/health` | El artefacto no arranca. |
| Resumen de tests y cobertura | bash + awk sobre los reportes | Siempre se ejecuta: publica en el *Job Summary* los tests por tipo y la cobertura de líneas y ramas. |
| Subir artefactos | `actions/upload-artifact` | Siempre se ejecuta: `reportes-tests` (surefire + failsafe), `reporte-cobertura` (HTML de JaCoCo) y el JAR. |

Luego el job `docker-build` construye la imagen y verifica que el contenedor quede `healthy`.

### Cobertura y umbrales

La cobertura se mide con **JaCoCo** combinando unitarias e integración (`target/site/jacoco/`: HTML, XML para
SonarCloud y CSV para el resumen del CI). Se excluyen del cálculo las clases sin lógica propia: la clase main
`CharmeetchicApiApplication`, los DTOs (records) y las entidades JPA (Lombok + mapeo con anotaciones).

| Regla | Líneas | Ramas | Medido al fijarla |
|---|---:|---:|---|
| Todo el proyecto (`BUNDLE`) | 93 % | 82 % | 97,0 % líneas / 86,0 % ramas |
| Paquete `service` | 95 % | 85 % | 97,3 % líneas / 88,1 % ramas |

Criterio:

- **Unos puntos bajo lo medido** (3–4): es alcanzable y deja margen para código nuevo razonable, pero un retroceso real
  (por ejemplo, una clase nueva sin pruebas o borrar tests) hace fallar `mvn verify`.
- **`service` más exigente** porque ahí viven las reglas de negocio que afectan dinero, stock y seguridad (totales e
  IVA, descuento de stock, transiciones de estado, validación de tokens y claves de Firebase).
- **Ramas además de líneas**: una línea con un `if` puede estar "cubierta" sin haber probado el camino de error; la
  cobertura de ramas obliga a probar ambos lados.
- Los umbrales están en `pom.xml` (`cobertura.*.min`). Se suben cuando la cobertura mejora de forma estable; **no se
  bajan** para hacer pasar un PR.

### Qué se considera un caso crítico

Un caso es crítico si un error en él puede vender sin stock, cobrar mal, exponer datos o dar acceso indebido. Todo
cambio que toque estos caminos debe traer su prueba:

- **Errores de validación**: datos de entrada inválidos, rangos de precio o fechas incorrectos, carrito vacío,
  transiciones de estado no permitidas.
- **Stock**: stock insuficiente (la orden se rechaza y el stock no cambia) y devolución de stock al cancelar.
- **Tokens**: token inválido, expirado, falsificado, de otro proyecto o con `kid` desconocido; claves de Google no
  disponibles (500, no 401); que no se pueda forzar la descarga de claves en cada petición.
- **Autorización**: acceso sin rol (403), órdenes de otro usuario, revocación del rol ADMIN en Firebase.
- **Recursos inexistentes**: producto, categoría u orden que no existe (404), sin modificar nada.
- **Errores internos**: un 500 o un error de base de datos no debe exponer detalles técnicos al cliente.

No se agregan pruebas triviales (getters, records, constructores) solo para subir el porcentaje.

### Un check en rojo bloquea el merge

`build-and-test` y `docker-build` son checks requeridos en la protección de `main` y `develop`. Si una prueba falla,
la cobertura baja del umbral, el JAR no arranca o la imagen no queda `healthy`, el check queda en rojo y **GitHub no
permite mergear el PR** hasta corregirlo con un nuevo commit en la misma rama.

---

## Estructura de carpetas

```
charmeetchic_api/
├── .github/
│   ├── workflows/
│   │   └── ci.yml                    Pipeline de CI (build, tests, smoke test, artefactos)
│   └── pull_request_template.md      Plantilla obligatoria de PR
├── docs/
│   └── workflow.md                   Diagramas del flujo GitFlow y del pipeline
├── src/
│   ├── main/
│   │   ├── java/com/charmeetchic/api/
│   │   │   ├── CharmeetchicApiApplication.java   Punto de entrada de Spring Boot
│   │   │   ├── config/        Configuración: seguridad, CORS, propiedades de JWT/Firebase
│   │   │   ├── controller/    Controladores REST (health, products, categories, orders, reports, profile)
│   │   │   ├── service/       Lógica de negocio y validación de tokens (JwtService, JwksKeyProvider)
│   │   │   ├── repository/    Interfaces Spring Data JPA de acceso a datos
│   │   │   ├── entity/        Modelo de dominio: entidades JPA (Product, Category, Order, OrderItem, User)
│   │   │   ├── enums/         Enumeraciones del modelo (OrderStatus, Role)
│   │   │   ├── dto/           Objetos de entrada/salida de la API, desacoplados de las entidades
│   │   │   ├── security/      Representación del usuario autenticado (AuthenticatedUser)
│   │   │   ├── filter/        Filtro que valida el JWT en cada request (JwtValidationFilter)
│   │   │   └── exception/     Excepciones de negocio y manejador global de errores (ApiError)
│   │   └── resources/
│   │       ├── application.yml   Configuración de la aplicación (puerto, context-path /api, H2, Firebase)
│   │       └── data.sql          Datos iniciales del catálogo
│   └── test/
│       └── java/com/charmeetchic/api/   Unitarias *Test (Mockito, surefire) e integración *IntegrationTest (MockMvc + H2, failsafe)
├── CONTRIBUTING.md
├── README.md
└── pom.xml                           Dependencias, build, separación de pruebas y cobertura (JaCoCo)
```

> El modelo de dominio vive en el paquete `entity` (no `model`), y el paquete de enumeraciones se llama `enums`
> porque `enum` es palabra reservada de Java.
