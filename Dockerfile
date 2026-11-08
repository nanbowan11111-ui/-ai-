FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend ./backend
COPY frontend ./frontend
WORKDIR /build/backend
RUN mvn -q package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/backend/target/supplies-ledger-0.1.0.jar app.jar
RUN mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
