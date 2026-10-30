
# Build Stage
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -ntp clean package -DskipTests -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.pool=false && \
    JAR_FILE=$(find target/ -maxdepth 1 -name "repopilot-backend-*.jar" ! -name "*-plain.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -n 1) && \
    if [ -z "$JAR_FILE" ]; then echo "Executable JAR not found in target/" && exit 1; fi && \
    cp "$JAR_FILE" app.jar

# Run Stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl wget docker-cli

COPY --from=builder /app/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
