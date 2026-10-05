FROM eclipse-temurin:21-alpine

WORKDIR /opt

ENV PORT=8080

EXPOSE 8080

COPY build/libs/*.jar /opt/app.jar

ENTRYPOINT ["java", "-jar", "/opt/app.jar"]
