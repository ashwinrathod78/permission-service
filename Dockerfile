FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build-artifact
ARG JAR_FILE=build/libs/permission-service-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} /permission-service.jar
COPY src/main/docker/HealthCheck.java /HealthCheck.java
RUN javac --release 25 -d /health /HealthCheck.java

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
WORKDIR /app
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
COPY --from=build-artifact --chown=10001:10001 /permission-service.jar /app/permission-service.jar
COPY --from=build-artifact --chown=10001:10001 /health/HealthCheck.class /app/HealthCheck.class
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=8 CMD ["java", "-cp", "/app", "HealthCheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/permission-service.jar"]
