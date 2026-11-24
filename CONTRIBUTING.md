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
| `security` | Seguridad del pipeline: permisos del workflow, SonarCloud, Trivy. Se usa con `ci` y `docs`. |
| `deps` | Actualización de dependencias (`build(deps)` para Maven y Docker, `ci(deps)` para actions). Lo usa Dependabot. |
| `db` | Base de datos: PostgreSQL y el perfil `k8s` (`application-k8s.yml`), `data.sql`. |
| `k8s` | Orquestación con Kubernetes: manifiestos de `k8s/` (Kustomize), el job `k8s-validate` del CI y `docs/kubernetes.md`. Se usa con `feat`, `ci` y `docs`. |
| `deploy` | Entrega continua: publicación de la imagen en GHCR (`publish-image`), despliegue automático (`deploy`), GitHub Environments y su documentación. Se usa con `ci` y `docs`. |
| `observability` | Observabilidad: Actuator/Micrometer y logs JSON de la API, stack de monitoreo de `k8s/observability/` (Prometheus, Grafana, Loki, Alloy, Pushgateway, alertas y dashboard), el job `deploy-aks` y `docs/observabilidad.md`. Se usa con `feat`, `fix`, `ci` y `docs`. |
| `compliance` | Cumplimiento automatizado: políticas OPA/Rego de `policy/`, sus tests, excepciones y el job `compliance-audit`. Se usa con `feat`, `fix`, `ci` y `docs`. |

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
6. **El merge despliega en staging**: el push a `develop` corre el workflow completo; `publish-image` publica la
   imagen en GHCR (tags `sha-<commit>` y `develop`) y `deploy` la despliega en el entorno **`staging`** (overlay dev)
   y la verifica con un smoke test. Revisar que el workflow del push termine en verde (pestaña *Actions* o
   *Deployments*); si falla, se corrige con un nuevo PR.

### develop → main (release)

1. Abrir un PR de `develop` hacia `main` cuando el conjunto de funcionalidades está listo y **staging está en verde**
   con ese mismo código (el último despliegue de `develop`).
2. **El merge a `main` despliega en production**: el push a `main` publica la imagen (tags `sha-<commit>` y `main`) y
   el job `deploy` la despliega en el entorno **`production`** (overlay prod: 3 réplicas, HPA 3-10, PDB 2). Si el
   environment `production` tiene *Required reviewers*, el despliegue espera la aprobación en la pestaña *Actions*.
3. Tras el merge, **esperar a que el pipeline de `main` publique la imagen** (`publish-image` en verde) y crear un
   **tag anotado** sobre ese commit de merge. El push del tag **promueve la imagen ya publicada, sin rebuild**: busca
   `sha-<commit>` en GHCR y le agrega los tags `X.Y.Z` y `X.Y` al **mismo digest** (`docker buildx imagetools
   create`). No vuelve a desplegar (ese commit ya está en production). Si `sha-<commit>` no existe, el job falla: el
   tag debe ir sobre un commit cuyo pipeline publicó la imagen.

   ```bash
   git checkout main && git pull
   git tag -a v1.0.0 -m "Primera versión estable: health con versión y filtro por precio"
   git push origin v1.0.0
   ```

   **Por qué sin rebuild:** el tag `sha-<commit>` es inmutable y el despliegue usa el digest. Antes, el tag `v3.0.0`
   reconstruyó la imagen y sobrescribió `sha-38a1369` con otro digest, y `deploy` se detuvo (incidente documentado
   en [docs/observabilidad.md](docs/observabilidad.md#incidente-el-tag-de-versión-sobrescribió-sha-commit-v300)). Lo
   que se publica como `X.Y.Z` es exactamente el artefacto que pasó los tests, Trivy y el despliegue. Un *re-run* de
   `publish-image` tampoco reconstruye: reutiliza el digest existente.

4. Si después de la release hubo cambios en `main` que no están en `develop` (un hotfix), hacer back-merge
   `main → develop` para que ambas ramas no diverjan.

| Paso | Rama | Entorno | Imagen en GHCR |
|---|---|---|---|
| Merge de un PR `feature/*` o `docs/*` | `develop` | `staging` | build: `sha-<commit>`, `develop` |
| Merge de la release o de un hotfix | `main` | `production` | build: `sha-<commit>`, `main` |
| Tag `vX.Y.Z` sobre `main` | — | (sin despliegue) | sin build: `X.Y.Z` y `X.Y` se agregan al digest de `sha-<commit>` |

Los despliegues (`deploy` y `deploy-aks`) usan la imagen **por digest** (`imagen@sha256:…`), no por tag.

Detalle de los jobs en [docs/workflow.md](docs/workflow.md#pipeline-de-cicd) y del despliegue en
[docs/kubernetes.md](docs/kubernetes.md#despliegue-automático).

### hotfix/* → main → develop

`hotfix/*` es una corrección urgente sobre `main` (producción). No siempre es código: el tipo del commit depende de
lo que se corrige (`fix(...)` para código, `docs(...)` para documentación, etc.).

1. Crear la rama desde `main`: `git checkout -b hotfix/firebase-project-id main`.
2. Aplicar la corrección y abrir un PR hacia `main`. En este proyecto el hotfix corrige documentación, con el commit
   `docs(readme): corrige Project ID de Firebase por defecto en el README`.
3. El merge a `main` despliega la corrección en **production** (job `deploy`). Tras el merge, crear el tag anotado de
   la versión de parche (`v1.0.1`).
4. Hacer **back-merge** de `main` a `develop` (PR `main → develop`) para que la corrección no se pierda en la
   siguiente release. Ese merge despliega en **staging**.

### Control automático: `gitflow-guard`

`main` es la rama por defecto del repositorio, así que GitHub la propone como base al abrir cualquier PR. Para que un
descuido no lleve trabajo sin integrar directo a producción, el job **`gitflow-guard`** del
[CI](.github/workflows/ci.yml) corre en cada PR hacia `main` y **falla si la rama de origen no es `develop` ni
empieza con `hotfix/`**:

| Rama de origen | PR hacia `main` | PR hacia `develop` |
|---|---|---|
| `develop` | ✅ permitido (release) | — |
| `hotfix/*` | ✅ permitido (corrección urgente) | ✅ (back-merge) |
| `feature/*`, `docs/*`, `fix/*` u otra | ❌ `gitflow-guard` en rojo | ✅ permitido |

Si falla, el log y el *Job Summary* explican el motivo. La corrección es **cambiar la base del PR a `develop`**
(botón *Edit* junto al título del PR → *base: develop*, o `gh pr edit <número> --base develop`); no hace falta cerrar
el PR ni rehacer los commits. El job no necesita permisos del `GITHUB_TOKEN` (`permissions: {}`), no hace checkout y
tiene un timeout de 2 minutos. El nombre de la rama se lee desde una variable de entorno, no interpolado en el
script, para que un nombre de rama con caracteres de shell no se ejecute.

**Incidente que lo motivó:** los PRs **#16** (`fix/skip-tests-docker`) y **#27** (`feature/k8s`) se fusionaron por
error en `main` en vez de `develop`, porque se abrieron con la base por defecto. `main` quedó con cambios que nunca
pasaron por `develop` (rama que se quedó atrás, sin `k8s/` ni el job `k8s-validate`), y hubo que crear
`feature/deploy` desde `main` y planificar un back-merge `main → develop` para volver a alinear las ramas. Con
`gitflow-guard` como check requerido en `main`, ninguno de los dos PRs se habría podido mergear.

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
  para poder mergear. `publish-image` y `deploy` **no** son checks requeridos: no corren en los PRs (solo después
  del merge, en el push a `develop` o `main`), así que exigirlos bloquearía todos los merges.
- **Check requerido `compliance-audit`** en `main` y `develop`: la auditoría de cumplimiento (políticas OPA/Rego con
  conftest, ver [docs/observabilidad.md](docs/observabilidad.md#políticas-de-cumplimiento)) debe terminar en verde.
  Corre en todos los PRs y falla ante cualquier incumplimiento: un contenedor privilegiado o sin `limits`, una imagen
  `latest`, un workflow sin `permissions`, una excepción de `.trivyignore` sin `exp:` o una credencial versionada
  bloquean el merge. `deploy-aks` **no** es check requerido (solo corre en el push a `main`).
- **Check requerido `gitflow-guard` solo en `main`**: bloquea los PRs hacia `main` que no vienen de `develop` ni de
  `hotfix/*` (ver [Control automático](#control-automático-gitflow-guard)). Se configura como requerido únicamente
  en la protección de `main`: en los PRs hacia `develop` el job no corre (cualquier rama de trabajo puede ir a
  `develop`), y si fuera requerido allí GitHub esperaría un check que nunca aparece.
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

Luego el job `docker-build` construye la imagen y verifica que el contenedor quede `healthy`, y en paralelo el job
`k8s-validate` renderiza los manifiestos de `k8s/` con `kustomize build` y los valida con `kubeconform -strict`
(ver [docs/kubernetes.md](docs/kubernetes.md)).

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

`build-and-test`, `docker-build` y `compliance-audit` son checks requeridos en la protección de `main` y `develop`.
Si una prueba falla, la cobertura baja del umbral, el JAR no arranca, la imagen no queda `healthy` o un manifiesto
incumple una política, el check queda en rojo y **GitHub no permite mergear el PR** hasta corregirlo con un nuevo
commit en la misma rama.

---

## Política de seguridad

La seguridad se revisa en cuatro capas, cada una con una herramienta distinta. Ninguna reemplaza a las otras: una
dependencia vulnerable puede no aparecer en el código, y un secreto expuesto no es una vulnerabilidad de librería.

### Qué analiza cada herramienta

| Herramienta | Qué analiza | Cuándo corre |
|---|---|---|
| **Dependabot** (`.github/dependabot.yml`) | Dependencias: `pom.xml` (Maven), imágenes base del `Dockerfile`, actions de los workflows y `scripts/firebase/package.json` (npm). Abre PRs hacia `develop` con la versión nueva. | Revisión semanal (lunes) y, para avisos de seguridad, apenas se publica el aviso. |
| **SonarCloud** (job `sonarcloud`) | Código fuente: bugs, vulnerabilidades, *security hotspots*, code smells, duplicación y cobertura (lee el XML de JaCoCo). Aplica el *quality gate* al código nuevo. | En cada PR hacia `develop`/`main` y en cada push a `develop`. |
| **Trivy** (job `docker-build`) | La imagen Docker ya construida: paquetes de Alpine, el JRE y los JAR de la aplicación (Spring, Tomcat, Jackson...). | En cada ejecución del workflow, después de construir la imagen. |
| **Secret scanning** (GitHub) | Credenciales en el repositorio (tokens, claves privadas, claves de Firebase/Google Cloud). Con *push protection* rechaza el push que contiene un secreto conocido. | En cada push, sobre todo el historial. |

Los secretos del pipeline (`SONAR_TOKEN`) se guardan como *secrets* de Actions y de Dependabot, nunca en archivos.
Los PRs de Dependabot reciben el secret de Dependabot, así que también pasan por SonarCloud.

### Qué bloquea el merge y qué solo alerta

| Resultado | Efecto |
|---|---|
| Trivy encuentra una vulnerabilidad **CRITICAL con corrección disponible** | **Bloquea**: falla `docker-build`, que es check requerido. |
| Una política de cumplimiento (conftest) no se cumple | **Bloquea**: falla `compliance-audit`, que es check requerido. Si el incumplimiento es inevitable en un componente de terceros, se documenta como excepción en `policy/data/exceptions.yaml` (regla, recurso, control, justificación y vencimiento ≤ 1 año). |
| Trivy encuentra vulnerabilidades **HIGH** (o CRITICAL sin corrección publicada) | Solo alerta: aparecen en el log del job y en *Security → Code scanning*. Las CRITICAL sin corrección se omiten (`ignore-unfixed`) porque no hay versión a la cual actualizar. |
| El *quality gate* de SonarCloud falla | Falla el job `sonarcloud`. **Bloquea solo si `sonarcloud` está configurado como check requerido** en la protección de `main` y `develop` (recomendado); mientras no lo esté, se ve en rojo en el PR pero no impide el merge. |
| Dependabot detecta una dependencia vulnerable | Alerta en *Security → Dependabot alerts* y abre un PR con la corrección. No bloquea por sí mismo; el PR pasa por el mismo CI. |
| Secret scanning detecta un secreto | Con *push protection*, el push se rechaza antes de llegar al repositorio. Si el secreto ya estaba, queda una alerta en *Security → Secret scanning*. |

**Criterio de Trivy**: una CRITICAL con corrección es grave y tiene solución concreta (subir de versión), así que no
hay motivo para dejarla llegar a `develop`. Las HIGH se atienden en PRs propios (normalmente los de Dependabot), sin
frenar todo el flujo por cada aviso nuevo.

### Dónde se ven las alertas

- **Pestaña Security del repositorio**: *Dependabot alerts*, *Code scanning* (resultados de Trivy, categoría
  `trivy-imagen`) y *Secret scanning*.
- **PRs de Dependabot**: llevan los labels `dependencies` y el del ecosistema (`java`, `github_actions`, `docker`, `javascript`);
  las actualizaciones menores y de parche de Maven llegan juntas en un solo PR semanal.
- **Dashboard de SonarCloud** (organización `dgomezpalacios`, proyecto `DGomezPalacios_charmeetchic_api`): issues,
  hotspots, cobertura y estado del quality gate. En cada PR, SonarCloud también deja el resultado como check.
- **Log del job `docker-build`**: tabla de Trivy con CRITICAL y HIGH.

### Cómo se gestiona una alerta

1. **Evaluar**: revisar el aviso (CVE, severidad, versión corregida) y si el código afectado se usa realmente en la API.
2. **Corregir** en una rama propia hacia `develop` (`fix/*` o `feature/*`; `hotfix/*` sobre `main` si ya está en
   producción y es grave), o aprobar el PR de Dependabot si el CI queda en verde. Commit con el scope que corresponda,
   por ejemplo `build(deps): actualiza Spring Boot a 3.5.x`.
3. **Verificar** que el check vuelva a verde y que la alerta se cierre sola en la pestaña Security al llegar a la rama
   analizada.
4. **Si no se puede corregir todavía** (no hay versión corregida o la actualización requiere una migración mayor):
   documentar el riesgo aceptado **con una justificación escrita** y abrir un issue para retomarla. En Trivy se agrega
   a `.trivyignore` con un comentario (qué es, por qué no aplica, por qué no se corrige ahora y cuándo se revisa) y
   una fecha de vencimiento `exp:AAAA-MM-DD`: al vencer, la vulnerabilidad vuelve a bloquear. Ejemplo actual:
   CVE-2026-47884 (`XsltView` de spring-webmvc, solo corregida en Spring Framework 7 / Spring Boot 4; la API no usa
   vistas XSLT). En GitHub o SonarCloud, la alerta se descarta con el mismo tipo de justificación. Nunca se ocultan
   hallazgos sin justificar.
5. **Secreto expuesto**: revocar y rotar la credencial de inmediato (borrarla del historial no basta: hay que asumir
   que ya fue copiada), actualizar el secret en GitHub y cerrar la alerta.

---

## Estructura de carpetas

```
charmeetchic_api/
├── .github/
│   ├── workflows/
│   │   └── ci.yml                    Pipeline de CI/CD (tests, cobertura, SonarCloud, imagen + Trivy, manifiestos k8s,
│   │                                 publicación en GHCR y despliegue automático en staging/production)
│   ├── dependabot.yml                Actualizaciones automáticas de dependencias (Maven, Docker, Actions, npm)
│   └── pull_request_template.md      Plantilla obligatoria de PR
├── docs/
│   ├── kubernetes.md                 Arquitectura en Kubernetes, despliegue en kind y automático, justificación de parámetros
│   ├── observabilidad.md             Métricas, logs, alertas, dashboard, políticas de cumplimiento, gates y AKS
│   └── workflow.md                   Diagramas del flujo GitFlow, del pipeline CI/CD y de la trazabilidad
├── policy/                           Políticas OPA/Rego (conftest) del job compliance-audit
│   ├── kubernetes/ workflow/ repo/   Reglas por objetivo y sus tests (*_test.rego)
│   ├── lib/                          Funciones comunes (pod spec, contenedores, excepciones)
│   ├── data/exceptions.yaml          Excepciones documentadas con vencimiento
│   ├── controles.json                Catálogo regla -> control normativo (lo usa el reporte)
│   └── scripts/                      compliance-audit.sh (renderiza, evalúa y reporta) y repo-facts.sh
├── k8s/                              Manifiestos de Kubernetes (Kustomize)
│   ├── kind-config.yaml              Clúster kind: 1 control-plane + 2 workers (Kubernetes 1.35.8); local y job deploy
│   ├── ci/smoke-test-pod.yaml        Pod con curl del smoke test del job deploy (/api/health y /api/products)
│   ├── base/                         Recursos comunes: Namespace, ConfigMap, Deployment, Service, HPA y PDB de la API;
│   │                                 StatefulSet, Service headless y NetworkPolicy de PostgreSQL; Secret de EJEMPLO
│   ├── observability/                Stack de monitoreo para AKS: values de Helm (versiones fijas en versions.env),
│   │                                 ServiceMonitor, PrometheusRule y dashboard de Grafana (JSON)
│   └── overlays/
│       ├── dev/                      kind: recursos bajos, HPA 2-3, JWT local para pruebas
│       ├── prod/                     Producción: 3+ réplicas, HPA 3-10, PDB 2, más recursos y disco
│       └── aks/                      AKS (1 nodo de 2 vCPU / 8 GB): prod ajustado, HPA 2-3; lo despliega deploy-aks
├── scripts/
│   ├── ci/push-deploy-metrics.sh     Publica las métricas del despliegue en el Pushgateway (job deploy-aks, por port-forward)
│   └── firebase/                     Script Node.js de alta de administradores (addAdmin.js: claim ADMIN en
│                                     Firebase). No lo usa la API ni entra en la imagen; Dependabot (npm) lo mantiene
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
│   │       ├── application.yml       Configuración de la aplicación (puerto, context-path /api, H2, Firebase)
│   │       ├── application-k8s.yml   Perfil k8s: PostgreSQL desde variables de entorno (Kubernetes)
│   │       └── data.sql              Datos iniciales del catálogo (idempotente, válido en H2 y PostgreSQL)
│   └── test/
│       └── java/com/charmeetchic/api/   Unitarias *Test (Mockito, surefire) e integración *IntegrationTest (MockMvc + H2, failsafe)
├── .trivyignore                      Riesgos aceptados de Trivy, con justificación y vencimiento
├── CONTRIBUTING.md
├── README.md
└── pom.xml                           Spring Boot 3.5 (parent), dependencias, pruebas, cobertura y SonarCloud
```

> El modelo de dominio vive en el paquete `entity` (no `model`), y el paquete de enumeraciones se llama `enums`
> porque `enum` es palabra reservada de Java.
