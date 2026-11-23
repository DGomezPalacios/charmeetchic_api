# -----------------------------------------------------------------------------
# Imagen del microservicio administrador de Kafka (build en dos etapas, mismo estilo que rabbit-admin/).
#
#   docker build -t charmeetchic-kafka-admin ./kafka-admin
#   docker run -p 8082:8082 -e KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092,kafka3:9092 charmeetchic-kafka-admin
#
# Etapa 1 compila con Maven; etapa 2 solo lleva el JRE y el JAR. Los tests no corren aquí: los corre
# `mvn verify` en el CI antes de construir la imagen.
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
COPY --from=build /build/target/charmeetchic-kafka-admin-*.jar app.jar
USER appuser
EXPOSE 8082
# JAVA_OPTS lo define el compose (p. ej. -Xmx224m). Por defecto la JVM usa hasta el 75 % del límite del contenedor.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
