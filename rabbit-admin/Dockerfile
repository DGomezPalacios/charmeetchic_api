# -----------------------------------------------------------------------------
# Imagen del microservicio administrador de RabbitMQ (build en dos etapas).
#
#   docker build -t charmeetchic-rabbit-admin ./rabbit-admin
#   docker run -p 8081:8081 -e RABBITMQ_ADDRESSES=rabbitmq:5672 \
#              -e RABBITMQ_MANAGEMENT_URL=http://rabbitmq:15672 charmeetchic-rabbit-admin
#
# Etapa 1 compila con Maven; etapa 2 solo lleva el JRE y el JAR (imagen más chica, sin código fuente
# ni Maven). Los tests no corren aquí: los corre `mvn verify` en el CI antes de construir la imagen.
# -----------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Primero solo el pom: si no cambian las dependencias, Docker reutiliza esta capa (builds más rápidos).
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# No correr como root dentro del contenedor.
RUN useradd --system --uid 1001 --no-create-home appuser
COPY --from=build /build/target/charmeetchic-rabbit-admin-*.jar app.jar
USER appuser
EXPOSE 8081
# JAVA_OPTS lo define docker-compose.prod.yml (p. ej. -Xmx224m) para repartir los 4 GB de la VM.
# Por defecto la JVM usa hasta el 75 % del límite de memoria del contenedor.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
