# -------------------------
# Build stage
# -------------------------
FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
WORKDIR /app
COPY . .
RUN ./mvnw -B verify

# -------------------------
# Runtime stage
# -------------------------
FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
WORKDIR /app
RUN mkdir -p /database && ln -sf /database /app/database
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
