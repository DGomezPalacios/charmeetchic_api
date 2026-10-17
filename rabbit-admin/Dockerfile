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
# MaxRAMPercentage: la JVM respeta el límite de memoria del contenedor (importante en una EC2 chica).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
