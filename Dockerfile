# -----------------------------------------------------------------------------
# Imagen del microservicio charmeetchic-api (Spring Boot 3.5, Java 17).
#
#   docker build -t charmeetchic-api:local .
#   docker run -d -p 8080:8080 charmeetchic-api:local
#
# Solo se copian pom.xml y src/: secretos (.env, claves de Firebase), node_modules y demás
# archivos locales nunca entran a la imagen (ver .dockerignore).
# Los tests no corren aquí: los ejecuta `mvn -B verify` en el job build-and-test del CI.
# -----------------------------------------------------------------------------

# ---------- Etapa 1: compilación (JDK + Maven, se descarta al final) ----------
# Imagen fijada por versión exacta (Maven 3.9.16 sobre Temurin JDK 17) y por digest: el tag puede
# volver a publicarse con otro contenido, el digest no. Así dos builds del mismo commit usan exactamente la
# misma imagen. Dependabot (ecosistema docker) propone las actualizaciones de tag y digest.
FROM maven:3.9.16-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS build
WORKDIR /build

# Cache de dependencias: primero solo el pom.xml. Mientras el pom no cambie, Docker reutiliza
# esta capa y no vuelve a descargar las dependencias aunque cambie el código fuente.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Recién ahora se copia el código; un cambio en src/ invalida solo desde aquí hacia abajo.
COPY src ./src
RUN mvn -B -q package -DskipTests \
    && cp target/charmeetchic-api-*.jar app.jar

# Layered jar con el modo tools (jarmode=layertools está obsoleto desde Spring Boot 3.3).
# Separa el JAR en carpetas que van de lo que menos cambia a lo que más cambia:
# dependencies, spring-boot-loader, snapshot-dependencies y application.
# --layers: una carpeta por capa. --launcher: deja el contenido con la estructura que espera JarLauncher
# (BOOT-INF/, META-INF/, org/springframework/boot/loader/), igual que hacía layertools.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- Etapa 2: ejecución (solo JRE, imagen liviana) ----------
# Variante alpine: JRE 17 sobre Alpine (musl). La app es Java puro (Spring Web, JPA, H2 en
# memoria, JJWT), sin librerías nativas, así que corre sin problemas y la imagen pesa mucho menos
# que la variante Ubuntu. Además trae BusyBox, que incluye wget para el HEALTHCHECK.
# Fijada a JRE 17.0.21.1 (build 2) por tag exacto y digest, por el mismo motivo que la etapa de build.
FROM eclipse-temurin:17.0.21.1_2-jre-alpine@sha256:2b4113ae80e31f103b284dd3bc3e008add670cf8555417b5b289fa1128f2f0a3
WORKDIR /app

# Usuario de sistema sin privilegios: si la app se ve comprometida, no tiene root en el contenedor.
RUN addgroup -S app && adduser -S -G app -u 1001 -H app

# Una capa por cada carpeta del layered jar, en orden de estabilidad. Al cambiar solo el código
# se reconstruye únicamente la última capa (application); las dependencias quedan en cache.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app
EXPOSE 8080

# Opciones de la JVM sobrescribibles con `docker run -e JAVA_OPTS=...`.
# MaxRAMPercentage ajusta el heap al límite de memoria del contenedor en vez de fijar un -Xmx.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Healthcheck con el wget de BusyBox (ya viene en Alpine): no hay que instalar curl, así que no
# se agregan paquetes ni capas. start-period da tiempo a que Spring Boot termine de arrancar.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8080/api/health || exit 1

# `exec` reemplaza al shell por la JVM (PID 1), así recibe SIGTERM y se apaga ordenadamente.
# JarLauncher (paquete loader.launch desde Boot 3.2) arranca la app desde las capas extraídas con --launcher.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
