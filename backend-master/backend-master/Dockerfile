FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system roadwise \
    && useradd --system --gid roadwise --no-create-home --shell /usr/sbin/nologin roadwise \
    && mkdir -p /app/uploads \
    && chown roadwise:roadwise /app/uploads
COPY --from=build --chown=roadwise:roadwise /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER roadwise
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
