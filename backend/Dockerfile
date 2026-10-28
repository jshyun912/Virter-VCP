FROM eclipse-temurin:25.0.4.1_1-jre

WORKDIR /app

COPY target/backend-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]