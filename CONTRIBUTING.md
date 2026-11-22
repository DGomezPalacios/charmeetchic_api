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
│       └── java/com/charmeetchic/api/   Tests unitarios (Mockito) y de integración (MockMvc + H2)
├── CONTRIBUTING.md
├── README.md
└── pom.xml                           Dependencias y build con Maven
```

> El modelo de dominio vive en el paquete `entity` (no `model`), y el paquete de enumeraciones se llama `enums`
> porque `enum` es palabra reservada de Java.
