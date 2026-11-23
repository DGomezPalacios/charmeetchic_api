# Flujo de trabajo y pipeline de CI

Diagramas del modelo de ramas GitFlow y del pipeline de integración continua de **charmeetchic-api**. Las reglas
completas están en [CONTRIBUTING.md](../CONTRIBUTING.md). Volver al [README](../README.md).

---

## Flujo de ramas (GitFlow)

```mermaid
gitGraph
    commit id: "estado inicial"
    commit id: "ci: workflow + plantilla PR"
    branch develop
    checkout develop
    branch feature/health-version
    checkout feature/health-version
    commit id: "feat(health)"
    commit id: "test(health)"
    commit id: "docs(readme) health"
    checkout develop
    merge feature/health-version id: "PR #1"
    branch feature/filtro-precio
    checkout feature/filtro-precio
    commit id: "feat(products)"
    commit id: "test(products)"
    commit id: "docs(readme) filtro"
    commit id: "fix(products) negativos"
    checkout develop
    merge feature/filtro-precio id: "PR #2"
    checkout main
    merge develop id: "PR #3 release" tag: "v1.0.0"
    checkout develop
    branch docs/guia-devops
    checkout docs/guia-devops
    commit id: "docs(contributing)"
    commit id: "docs(workflow)"
    commit id: "docs(readme) devops"
    checkout develop
    merge docs/guia-devops id: "PR docs"
    checkout main
    branch hotfix/firebase-project-id
    checkout hotfix/firebase-project-id
    commit id: "docs(readme): corrige Project ID de Firebase por defecto en el README"
    checkout main
    merge hotfix/firebase-project-id id: "PR hotfix" tag: "v1.0.1"
    checkout develop
    merge main id: "back-merge"
    checkout main
    merge develop id: "PR release v1.1.0" tag: "v1.1.0"
```

Secuencia real de versiones:

1. **Release `v1.0.0`**: el PR #3 lleva `develop` (PR #1 y PR #2) a `main`; el commit de merge se marca con el tag
   anotado `v1.0.0`.
2. **Documentación a `develop`**: `docs/guia-devops` (esta guía, `CONTRIBUTING.md` y la sección DevOps del README)
   se integra en `develop` mediante PR. Todavía no llega a `main`.
3. **Hotfix `v1.0.1`**: `hotfix/*` es una corrección urgente sobre `main` (producción). En este proyecto corrige
   documentación: el Project ID de Firebase por defecto indicado en el README de producción. Sale de `main`, vuelve
   a `main` con el tag `v1.0.1` y se hace back-merge de `main` a `develop` para no perder la corrección.
4. **Release `v1.1.0`**: un nuevo PR `develop → main` lleva la documentación y las convenciones a producción, junto
   con la corrección del hotfix ya incorporada en `develop`.

---

## Pipeline de CI

```mermaid
flowchart TD
    A["Evento: push a develop / PR hacia develop o main"] --> B["Runner ubuntu-latest"]
    B --> C["Checkout del código"]
    C --> D["Configurar JDK 17 (Temurin + cache Maven)"]
    D --> E["mvn -B test<br/>compilación + pruebas unitarias"]
    E -->|falla| X["Check en rojo"]
    E -->|ok| E2["mvn -B verify -DskipUTs=true<br/>integración + JAR + cobertura JaCoCo"]
    E2 -->|"falla o cobertura bajo umbral"| X
    E2 -->|ok| F["Smoke test: java -jar + curl /api/health"]
    F -->|falla| X
    F -->|ok| G["Job Summary + artefactos<br/>reportes-tests, reporte-cobertura, JAR"]
    G --> H["build-and-test en verde"]
    H --> S1["Job sonarcloud<br/>mvn verify + análisis SonarCloud"]
    H --> D1["Job docker-build<br/>build de la imagen (imágenes base fijadas)"]
    S1 --> S2{"¿Quality gate OK?"}
    S2 -->|no| X
    S2 -->|sí| H2["Checks en verde"]
    D1 --> T1["Trivy: SARIF + tabla<br/>CRITICAL y HIGH, ignore-unfixed"]
    T1 --> D2["Contenedor healthy + /api/health"]
    D2 -->|falla| X
    D2 -->|ok| T2{"Trivy: ¿CRITICAL<br/>con corrección?"}
    T2 -->|sí| X
    T2 -->|no| H2
    T1 -.->|"siempre (if: always())"| SA["SARIF a Security → Code scanning"]
    H2 --> I{"¿Checks requeridos OK<br/>y conversaciones resueltas?"}
    I -->|sí| J["Merge permitido"]
    I -->|no| K["Merge bloqueado"]
    X --> K
```

> El resumen y los reportes (`reportes-tests`, `reporte-cobertura`) se publican siempre (`if: always()`), incluso si
> el job falló, para poder diagnosticar el error. La política de umbrales está en
> [CONTRIBUTING.md](../CONTRIBUTING.md#política-de-calidad).
>
> `sonarcloud` y `docker-build` corren en paralelo, ambos después de `build-and-test`. Los checks requeridos son
> `build-and-test` y `docker-build`; `sonarcloud` bloquea el merge solo si se agrega como check requerido en la
> protección de ramas. Las vulnerabilidades HIGH de Trivy se reportan sin bloquear. Detalle en
> [Política de seguridad](../CONTRIBUTING.md#política-de-seguridad).
>
> Fuera del workflow, **Dependabot** revisa cada semana Maven, Docker y las actions y abre PRs hacia `develop`, y
> **secret scanning** con *push protection* rechaza pushes que contengan credenciales.

---

## Cuándo se ejecuta el workflow

| Evento | Rama | ¿Se ejecuta el workflow? | Efecto |
|---|---|---|---|
| `push` | `develop` | Sí | Valida la integración después de cada merge en `develop`; el resultado se refleja en el badge del README. |
| `pull_request` | hacia `develop` | Sí | El check `build-and-test` aparece en el PR y es requerido para mergear. |
| `pull_request` | hacia `main` | Sí | Valida la release o el hotfix antes de llegar a producción; check requerido. |
| `push` | `feature/*` | No | No hay ejecución hasta que se abre el PR hacia `develop` (el workflow solo escucha `push` a `develop`). |

---

## Steps de `.github/workflows/ci.yml`

El workflow tiene tres jobs que corren en `ubuntu-latest`. Configuración común a todos:

- **`permissions: contents: read`** a nivel global: el `GITHUB_TOKEN` solo puede leer el código. Solo
  `docker-build` pide además `security-events: write`, para subir el SARIF de Trivy.
- **`concurrency`** por PR (o por rama en los push) con `cancel-in-progress`: un commit nuevo cancela la ejecución
  anterior, que ya valida un estado obsoleto.
- **`timeout-minutes: 20`** en cada job, para cortar un job colgado.
- Las actions de terceros (`docker/*`, `aquasecurity/*`) están fijadas por el SHA completo del commit, con la versión
  en un comentario; Dependabot las actualiza. Las oficiales de GitHub (`actions/*`, `github/*`) usan tag de versión.

Job `build-and-test`:

1. **Checkout del código** (`actions/checkout@v4`): descarga el repositorio en el runner.
2. **Configurar JDK 17** (`actions/setup-java@v5`): instala Temurin 17 y activa el cache de dependencias Maven para
   acelerar las siguientes ejecuciones.
3. **Pruebas unitarias** (`mvn -B test`): compila y ejecuta solo las pruebas unitarias (surefire).
4. **Pruebas de integración y verificación de cobertura** (`mvn -B verify -DskipUTs=true`): ejecuta las pruebas
   `*IntegrationTest` (failsafe), empaqueta el JAR, genera el reporte de JaCoCo (unitarias + integración) y aplica
   los umbrales de cobertura. No recompila ni repite las unitarias.
5. **Smoke test del JAR**: levanta el JAR generado en segundo plano y consulta `GET /api/health` hasta 30 veces (cada
   2 segundos). Si la aplicación no responde, el job falla; así se verifica que el artefacto realmente arranca.
6. **Publicar resumen de tests y cobertura** (`if: always()`): script bash + awk que escribe en el *Job Summary* los
   tests por tipo y la cobertura de líneas y ramas.
7. **Subir reportes** (`actions/upload-artifact@v4`, `if: always()`): `reportes-tests` (surefire + failsafe) y
   `reporte-cobertura` (HTML de JaCoCo), aunque los pasos anteriores hayan fallado.
8. **Subir JAR generado** (`actions/upload-artifact@v4`): publica el JAR como artefacto `charmeetchic-api-jar`,
   descargable desde la ejecución en la pestaña *Actions*.

Job `sonarcloud` (`needs: build-and-test`; no corre en PRs desde forks, que no reciben secrets):

1. **Checkout con `fetch-depth: 0`**: historial completo, que SonarCloud usa para distinguir el código nuevo.
2. **JDK 17 con cache de Maven** y **cache de `~/.sonar/cache`** (`actions/cache@v4`).
3. **`mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.qualitygate.wait=true`**: corre
   las pruebas, genera el XML de JaCoCo y envía el análisis. La versión del plugin, la organización y el proyecto están
   en el `pom.xml`; el token llega como variable de entorno `SONAR_TOKEN` desde los secrets. El job falla si el
   *quality gate* falla.

Job `docker-build` (`needs: build-and-test`):

1. **Construir imagen** con Buildx (sin publicarla), con las imágenes base fijadas por versión y digest. La app
   (Spring Boot 3.5) se extrae en capas con `jarmode=tools` y arranca con `JarLauncher`.
2. **Trivy: reporte SARIF** (CRITICAL y HIGH, `ignore-unfixed`) y **tabla en el log**; ninguno de los dos falla.
3. **Verificar contenedor**: lo levanta, espera el estado `healthy` y consulta `/api/health`.
4. **Trivy: gate**: falla el job si hay alguna vulnerabilidad CRITICAL con corrección disponible. Los tres pasos de
   Trivy leen `.trivyignore` (`trivyignores`), donde se documentan los riesgos aceptados con fecha de vencimiento.
5. **Subir SARIF a Code Scanning** (`github/codeql-action/upload-sarif@v4`, `if: always()`): los resultados quedan en
   *Security → Code scanning* aunque el gate haya fallado.
