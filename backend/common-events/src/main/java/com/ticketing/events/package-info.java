/**
 * common-events — 모듈 간 비동기 이벤트 계약 정의.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>{@link com.ticketing.events.Envelope} — 모든 Kafka 메시지가 감싸는 공통 봉투(트레이스/버전/타입 메타).</li>
 *   <li>{@link com.ticketing.events.Topics} — 토픽명 상수. 오타로 인한 정적 미스매치를 컴파일 타임에 차단.</li>
 * </ul>
 *
 * <h2>네이밍 규칙</h2>
 * <p>{@code <bounded-context>.<event>.v<n>} — 예: {@code seat.attempted.v1}.</p>
 * <p>
 *   메이저 호환성 깨짐 → 새 토픽 {@code .v2} 추가, consumer 가 마이그레이션 완료한 뒤 v1 retire.
 *   필드 추가만으로 호환되면 동일 버전 유지.
 * </p>
 *
 * <h2>의존성 정책</h2>
 * <p>
 *   이 모듈은 모든 module-* 가 의존하므로 무거운 라이브러리 의존을 두지 않는다.
 *   Jackson databind 만 허용(JsonNode payload 직렬화에 필수).
 * </p>
 */
package com.ticketing.events;
