# Multi-stage Dockerfile for the lex-xml-transform service.
# Stage 1 builds the fat jar; stage 2 ships a slim JRE-only image.

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY pom.xml mvnw* ./
COPY .mvn .mvn
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven && \
    mvn -B -DskipTests package && \
    cp target/lex-xml-transform-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 1001 lex && mkdir -p /app/out && chown -R lex:lex /app
COPY --from=build /workspace/app.jar /app/app.jar
COPY --from=build /workspace/src/main/resources/schemas /app/schemas
COPY --from=build /workspace/src/main/resources/xslt /app/xslt
COPY --from=build /workspace/src/main/resources/application.yml /app/application.yml
USER lex
EXPOSE 8080
ENV LEX_INGEST_OUTPUT_DIR=/app/out \
    LEX_INGEST_XSD_PATH=/app/schemas/judgment.xsd \
    LEX_INGEST_XSLT_PATH=/app/xslt/judgment-to-json.xsl
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
    CMD wget -q -O - http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
