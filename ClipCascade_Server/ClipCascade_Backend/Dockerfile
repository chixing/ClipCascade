# -------------------------
# Build stage
# -------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./mvnw -DskipTests package

# -------------------------
# Runtime stage
# -------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN mkdir -p /database && ln -sf /database /app/database
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
