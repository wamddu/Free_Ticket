FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN sed -i 's/\r$//' gradlew
# CD passes RUN_TESTS=false because the CI job has already run the tests (with MySQL) for this commit.
ARG RUN_TESTS=true
RUN --mount=type=cache,target=/root/.gradle if [ "$RUN_TESTS" = "true" ]; then         sh ./gradlew --no-daemon test bootJar; else sh ./gradlew --no-daemon bootJar; fi

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
