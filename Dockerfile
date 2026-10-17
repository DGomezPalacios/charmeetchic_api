# -----------------------------------------------------------------------------
# Imagen de la API de inventario (build en dos etapas, mismo estilo que rabbit-admin/).
#
#   docker build -t charmeetchic-api .
#
# Solo se copian pom.xml y src/: la clave de Firebase Admin, node_modules, .env y las otras apps
# (rabbit-admin/, gateway/) nunca entran a la imagen (ver también .dockerignore).
# Los tests no corren aquí: los corre `mvn verify` en el CI.
# -----------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 --no-create-home appuser
COPY --from=build /build/target/charmeetchic-api-*.jar app.jar
USER appuser
EXPOSE 8080
# JAVA_OPTS lo define docker-compose.prod.yml (p. ej. -Xmx448m) para repartir los 4 GB de la VM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
