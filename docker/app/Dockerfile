FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /project/backend
COPY backend/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
COPY frontend /project/frontend
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -H medlocker
WORKDIR /app
COPY --from=build /project/backend/target/medlocker-*.jar ./medlocker.jar
USER medlocker
ENTRYPOINT ["java", "-jar", "/app/medlocker.jar"]
