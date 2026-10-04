// =============================================================================
// settings.gradle.kts — 멀티모듈 루트 설정
// -----------------------------------------------------------------------------
// 이 파일이 "backend 프로젝트가 어떤 서브프로젝트들로 구성되는지" 알려주는 진입점.
// Gradle 은 settings.gradle.kts 가 있는 디렉터리를 루트 프로젝트로 인식한다.
//
// rootProject.name 은 빌드 산출물/IDE 표기에 쓰이므로 의미 있는 이름을 줘야 한다.
// =============================================================================
rootProject.name = "ticketing-backend"

// pluginManagement: Spring Boot/dependency-management 플러그인은 Gradle Plugin Portal 에서.
// 서브모듈에서 plugins { id("org.springframework.boot") apply false } 식으로 끌어오면
// 여기 지정한 repository 로부터 해석된다.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// dependencyResolutionManagement:
//   - FAIL_ON_PROJECT_REPOS = 서브프로젝트가 자체 repositories { ... } 를 못 적게 막아
//     중앙(이 파일) 한곳에서만 외부 저장소를 관리하도록 강제한다.
//   - Version Catalog 자동 등록: Gradle 8 부터 gradle/libs.versions.toml 은 별도 설정 없이
//     `libs.*` 로 모든 서브프로젝트에서 접근 가능하므로 versionCatalogs 블록은 불필요.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// -----------------------------------------------------------------------------
// 서브모듈 11개 등록
// -----------------------------------------------------------------------------
// app-gateway 가 모든 module-*/common-* 를 implementation 으로 끌어와 단일 프로세스로 부트런한다.
// 모듈러 모놀리스: 경계는 코드 레벨에서 지키되 배포 단위는 하나.
include(
    ":app-gateway",
    ":module-auth",
    ":module-show",
    ":module-queue",
    ":module-seat",
    ":module-payment-saga",
    ":module-pricing",
    ":module-alert",
    ":module-ws-bridge",
    ":common-events",
    ":common-domain",
    // Phase 3a: 트랜잭션 outbox 인프라 (seat / payment / alert 가 공유)
    ":common-outbox",
)
