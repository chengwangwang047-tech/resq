FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY target/resq-0.0.1-SNAPSHOT.jar app.jar

USER 10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
