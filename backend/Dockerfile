# ── Build stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Maven (Alpine repo) – se cachea en su propia capa
RUN apk add --no-cache maven

# 1. Descargar dependencias primero (capa cacheada; solo cambia si pom.xml cambia)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# 2. Compilar y empaquetar (solo re-ejecuta cuando cambia /src)
COPY src ./src
RUN mvn -q package -DskipTests

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Usuario sin privilegios
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder --chown=spring:spring /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
