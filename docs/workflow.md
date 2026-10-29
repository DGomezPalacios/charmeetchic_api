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
    merge develop id: "release" tag: "v1.0.0"
    branch hotfix/firebase-project-id
    checkout hotfix/firebase-project-id
    commit id: "fix(config) project-id"
    checkout main
    merge hotfix/firebase-project-id id: "hotfix" tag: "v1.0.1"
    checkout develop
    merge main id: "back-merge"
```

- Las features salen de `develop` y vuelven a `develop` mediante PR con merge commit.
- La release lleva `develop` a `main` y se marca con el tag anotado `v1.0.0`.
- El hotfix sale de `main`, vuelve a `main` con el tag `v1.0.1` y se hace back-merge a `develop`.

---

## Pipeline de CI

```mermaid
flowchart TD
    A["Evento: push a develop / PR hacia develop o main"] --> B["Runner ubuntu-latest"]
    B --> C["Checkout del código"]
    C --> D["Configurar JDK 17 (Temurin + cache Maven)"]
    D --> E["mvn -B verify<br/>compilación + tests + JAR"]
    E -->|falla| X["Check build-and-test en rojo"]
    E -->|ok| F["Smoke test: java -jar + curl /api/health"]
    F -->|falla| X
    F -->|ok| G["Subir artefactos<br/>surefire-reports + JAR"]
    G --> H["Check build-and-test en verde"]
    H --> I{"¿Check requerido OK<br/>y conversaciones resueltas?"}
    I -->|sí| J["Merge permitido"]
    I -->|no| K["Merge bloqueado"]
    X --> K
```

> Los reportes de tests (`surefire-reports`) se suben siempre (`if: always()`), incluso si el job falló, para poder
> diagnosticar el error.

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

El workflow tiene un único job, `build-and-test`, que corre en `ubuntu-latest`:

1. **Checkout del código** (`actions/checkout@v4`): descarga el repositorio en el runner.
2. **Configurar JDK 17** (`actions/setup-java@v4`): instala Temurin 17 y activa el cache de dependencias Maven para
   acelerar las siguientes ejecuciones.
3. **Compilar y ejecutar tests** (`mvn -B verify`): compila, ejecuta los tests unitarios y de integración y empaqueta
   el JAR. Si algún test falla, el job falla.
4. **Smoke test del JAR**: levanta el JAR generado en segundo plano y consulta `GET /api/health` hasta 30 veces (cada
   2 segundos). Si la aplicación no responde, el job falla; así se verifica que el artefacto realmente arranca.
5. **Subir reportes de tests** (`actions/upload-artifact@v4`, `if: always()`): publica `target/surefire-reports/`
   como artefacto `surefire-reports`, aunque los pasos anteriores hayan fallado.
6. **Subir JAR generado** (`actions/upload-artifact@v4`): publica el JAR como artefacto `charmeetchic-api-jar`,
   descargable desde la ejecución en la pestaña *Actions*.
