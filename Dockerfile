FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run test && npm run build
FROM maven:3.9-eclipse-temurin-21 AS java
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src/ ./src/
COPY --from=web /web/dist/ ./src/main/resources/static/
COPY docs/api/openapi.json docs/api/protocol-v1.md docs/api/device-binding-v1.md ./src/main/resources/static/docs/
RUN mvn -B test package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S memo && adduser -S memo -G memo && mkdir /data && chown memo:memo /data
COPY --from=java /build/target/memo-meeting-0.1.0.jar ./app.jar
USER memo
ENV DATA_DIR=/data BIND_ADDRESS=0.0.0.0 JAVA_TOOL_OPTIONS="-Xms64m -Xmx384m"
EXPOSE 8090
ENTRYPOINT ["java","-jar","/app/app.jar"]
