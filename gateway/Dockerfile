# -----------------------------------------------------------------------------
# Imagen del API Gateway (build en dos etapas, mismo estilo que rabbit-admin/ y el backend).
#
#   docker build -t charmeetchic-gateway ./gateway
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
COPY --from=build /build/target/charmeetchic-gateway-*.jar app.jar
USER appuser
EXPOSE 8000
# JAVA_OPTS lo define docker-compose.prod.yml para repartir los 4 GB de la VM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
