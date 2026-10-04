// =============================================================================
// common-domain — 모든 모듈이 공유하는 도메인 공통 코드
// -----------------------------------------------------------------------------
// 무엇:
//   - BusinessException / ErrorCode: 도메인 위반을 상위 레이어로 던지는 표준 채널.
//   - AppClock: java.time.Clock 빈을 통해 시간을 주입식으로 다뤄 테스트 가능성을 확보.
//   - (추후) UUID 유틸, Result 타입 등.
//
// 왜 별도 모듈인가:
//   - common-events 와 다르게 "통신 페이로드" 가 아니라 "내부 코드" 의 공통이라 분리.
//   - 어떤 모듈도 다른 모듈을 직접 의존하지 않지만 common-domain 만은 모두가 의존해도 됨(아래쪽 레이어).
//
// 의존성:
//   - spring-context: @Bean / @Configuration 사용을 위해 최소한만.
//   - validation: BusinessException 메시지에 javax.validation 메타데이터를 얹기 위해.
// =============================================================================

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
}
