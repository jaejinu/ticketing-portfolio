# =============================================================================
# 백엔드 런타임 이미지 — k3d 배포용
# -----------------------------------------------------------------------------
# 빌드는 호스트 Gradle 이 한다 (./gradlew :app-gateway:bootJar). 이미지는 실행만.
#   왜 멀티스테이지(이미지 안 Gradle 빌드)가 아닌가:
#   - 로컬 데모 반복 빌드에서 Gradle 캐시를 호스트 것 그대로 쓰는 편이 수십 배 빠르다.
#   - CI 로 옮길 때 멀티스테이지로 바꾸면 된다 (구조상 COPY 한 줄 차이).
#
# 사용:
#   ./gradlew :app-gateway:bootJar
#   docker build -t ticketing/backend:dev backend/
# =============================================================================

FROM eclipse-temurin:17-jre

WORKDIR /app

# bootJar 산출물 (settings.gradle 의 bootJar archiveFileName = app-gateway.jar)
COPY app-gateway/build/libs/app-gateway.jar /app/app.jar

# 컨테이너 기본 포트 — SERVER_PORT env 로 오버라이드 가능 (기본 8080)
EXPOSE 8080

# 컨테이너 메모리 한도를 존중하도록 MaxRAMPercentage 사용 (-Xmx 하드코딩 대신)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
