# The jar is built by ./mvnw (see run.sh), so the image only needs a JRE.
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 ledger
USER ledger
WORKDIR /app
COPY target/ledger-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
