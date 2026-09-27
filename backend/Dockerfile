# 빌드 단계: Gradle wrapper로 실행 가능한 jar를 만든다 (테스트는 CI·로컬에서 따로 돌린다)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# 실행 단계
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Seoul
WORKDIR /app
RUN useradd --system --uid 1001 evision
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
USER evision
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
