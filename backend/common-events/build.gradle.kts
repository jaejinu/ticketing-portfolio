// =============================================================================
// common-events — 모듈 간 비동기 이벤트 계약(Contract)
// -----------------------------------------------------------------------------
// 이 모듈 안의 클래스/상수가 변하면 "Kafka 토픽 호환성" 이 깨질 수 있다.
// 따라서 변경 시:
//   1) Envelope/Topics 변경 → 메이저 버전 토픽 분리(`xxx.v2`) 또는 하위호환 필드 추가만 허용.
//   2) JSON 스키마 추가 시 src/main/resources/schemas 아래 함께 둔다.
//
// 의존성은 최소화한다 — 이 모듈은 모든 module-* 가 의존하기 때문에 무겁게 만들면 전염된다.
// =============================================================================

dependencies {
    // Jackson 만 필요. JsonNode payload 직렬화/역직렬화에 사용.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(project(":common-domain"))
}
