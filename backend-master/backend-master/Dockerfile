FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
COPY . .
RUN chmod +x mvnw
# Run the test suite as part of the image build so a broken revision cannot
# be published. Tests use an in-memory H2 database and mocked external services.
RUN ./mvnw clean package
EXPOSE 8080
CMD ["java", "-jar", "target/backend-0.0.1-SNAPSHOT.jar"]