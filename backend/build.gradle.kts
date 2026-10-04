import java.io.File

// =============================================================================
// 루트 build.gradle.kts — 모든 서브프로젝트 공통 설정
// -----------------------------------------------------------------------------
// 여기 정의한 내용은 subprojects { } 블록 안에서 11개 모듈 전체에 일괄 적용된다.
// 모듈마다 똑같은 boilerplate 를 반복하지 않으려는 목적이다.
//
// 주의: app-gateway 만 spring-boot 플러그인을 "apply true" 한다 (executable jar 생성).
//      나머지 module-* / common-* 는 라이브러리 jar 만 생산하므로 plain jar 면 충분.
// =============================================================================

plugins {
    // 모든 서브모듈이 java 라이브러리/애플리케이션이므로 java 플러그인은 공통으로 켠다.
    java
    // 의존성 BOM(Spring Boot) 적용을 위해 dependency-management 도 공통 적용.
    alias(libs.plugins.spring.dependency.management) apply false
    // spring-boot 플러그인은 app-gateway 만 apply 한다. 루트에는 apply false 로만 선언.
    alias(libs.plugins.spring.boot) apply false
}

// 루트 자체는 산출물이 없으므로 jar 비활성.
tasks.named<Jar>("jar") { enabled = false }

// -----------------------------------------------------------------------------
// 모든 서브프로젝트 공통 설정
// -----------------------------------------------------------------------------
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.ticketing"
    version = "0.0.1-SNAPSHOT"

    // Java 17 — Spring Boot 3.3 의 최소 요구 버전과 일치.
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    // Spring Boot BOM 을 모든 모듈에 imports.
    // -> spring-boot-starter-* / spring-kafka / flyway-core 등은 버전 명시 없이 사용 가능.
    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${rootProject.libs.versions.spring.cloud.get()}")
            mavenBom("org.testcontainers:testcontainers-bom:${rootProject.libs.versions.testcontainers.get()}")
        }
    }

    // 공통 저장소는 settings.gradle.kts 에 중앙 집중되어 있으므로 여기엔 적지 않는다.

    // Lombok 은 모든 모듈에서 컴파일러 어노테이션 프로세서로 동작.
    // (compileOnly + annotationProcessor 둘 다 필요)
    dependencies {
        val libs = rootProject.libs
        "compileOnly"(libs.lombok)
        "annotationProcessor"(libs.lombok)
        "testCompileOnly"(libs.lombok)
        "testAnnotationProcessor"(libs.lombok)

        // Spring Boot 설정 클래스(@ConfigurationProperties) 메타데이터 자동 생성.
        "annotationProcessor"(libs.spring.boot.configuration.processor)

        // 모든 모듈은 JUnit 5 + AssertJ + Spring Test 기본 제공.
        "testImplementation"(libs.spring.boot.starter.test)
    }

    // JUnit 5 활성화
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 부하 테스트 격리: 통합 테스트가 폭주해도 8GB 환경에서 죽지 않도록 메모리 상한 설정.
        maxHeapSize = "1g"

        // macOS Docker Desktop 4.x 의 socket 경로 보정.
        // Testcontainers 가 /var/run/docker.sock 만 탐색하다 실패하는 케이스 + Docker Desktop 의
        // 표준 socket(~/.docker/run/docker.sock) 이 raw HTTP /info 호출에 빈 응답을 주는 환경 이슈를
        // 모두 해결하기 위해 우선순위를 명시:
        //   1) 환경변수 DOCKER_HOST 가 있으면 그대로 (CI/Linux 표준 경로 / 사용자 명시 우선)
        //   2) ~/Library/Containers/com.docker.docker/Data/docker.raw.sock 이 존재하면 그것 ←
        //      Docker Desktop 의 진짜 Docker Engine API socket. 표준 경로가 안 먹히는 환경에서
        //      raw HTTP 응답을 정상으로 돌려준다.
        //   3) ~/.docker/run/docker.sock 표준 경로 (대다수 환경에서 동작)
        // 환경변수 DOCKER_HOST 와 시스템 프로퍼티 docker.host 둘 다 채워 Testcontainers 의
        // EnvironmentAndSystemPropertyClientProviderStrategy 가 어느 쪽이든 잡도록 한다.
        val explicitDockerHost = System.getenv("DOCKER_HOST")
        val rawSock = File(
            System.getProperty("user.home"),
            "Library/Containers/com.docker.docker/Data/docker.raw.sock")
        val desktopSock = File(
            System.getProperty("user.home"), ".docker/run/docker.sock")
        val resolvedDockerHost: String? = when {
            !explicitDockerHost.isNullOrBlank() -> explicitDockerHost
            rawSock.exists() -> "unix://${rawSock.absolutePath}"
            desktopSock.exists() -> "unix://${desktopSock.absolutePath}"
            else -> null
        }
        if (resolvedDockerHost != null) {
            environment("DOCKER_HOST", resolvedDockerHost)
            systemProperty("docker.host", resolvedDockerHost)
        }
        // docker-java 클라이언트의 기본 API version 이 1.32 라 신버전 Docker Desktop 의 raw socket
        // (1.40+ 만 허용) 에서 BadRequest 가 난다. 명시 1.43 으로 negotiation 우회.
        // Docker Engine 24+ 가 지원하는 안정 버전. 너무 높으면 옛 Linux 환경에서 깨질 수 있어 보수적.
        systemProperty("api.version", "1.43")
        environment("DOCKER_API_VERSION", "1.43")
        // Ryuk reaper 비활성화 — macOS Docker Desktop 의 file sharing 제한으로 docker.raw.sock 을
        // 컨테이너 안에 마운트할 수 없어 ryuk container 자체가 안 떠진다. ryuk 없이도 JVM 종료 시
        // testcontainers 의 ResourceReaper.JVMHook 이 컨테이너를 정리하므로 동작에 무해.
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    }

    // 컴파일 옵션: 매개변수명 보존(파라미터 바인딩에 필요), 경고 표시.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
    }
}

// 루트 프로젝트에서 libs 카탈로그 접근용 헬퍼.
// (subprojects 블록 안에서 rootProject.libs 로 접근하려면 필요)
val org.gradle.api.Project.libs
    get() = the<org.gradle.accessors.dm.LibrariesForLibs>()
