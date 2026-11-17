FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY data/processed ./data/processed
COPY data/cenarios.json ./data/cenarios.json
COPY data/produtos-manuais.json ./data/produtos-manuais.json
RUN mkdir -p /app/data/db
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseSerialGC -Xms128m -Xmx384m -Dserver.port=${PORT} -jar /app/app.jar"]
