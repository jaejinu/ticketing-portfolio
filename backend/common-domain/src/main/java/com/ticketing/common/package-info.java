/**
 * common-domain — 모든 모듈이 공유하는 도메인 공통 기반.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>{@link com.ticketing.common.error.BusinessException} — 비즈니스 규칙 위반의 표준 예외 채널.</li>
 *   <li>{@link com.ticketing.common.error.ErrorCode} — 도메인 전체에서 공유되는 에러 코드 enum.</li>
 *   <li>{@link com.ticketing.common.time.AppClock} — {@link java.time.Clock} 빈 주입식 시간(테스트에서 고정 시계로 대체 가능).</li>
 * </ul>
 *
 * <h2>경계</h2>
 * <p>
 *   이 모듈은 Spring/JPA 같은 무거운 기술 의존성을 두지 않는 가장 하위 레이어다.
 *   다른 module-* 가 모두 의존해도 안전하도록 가벼움을 유지한다.
 * </p>
 *
 * <h2>변경 시 영향</h2>
 * <p>
 *   ErrorCode 에 항목을 추가/이름변경하면 전 모듈이 컴파일 영향을 받는다.
 *   기존 코드 의미가 바뀌지 않도록 신중하게 추가하고, 제거는 deprecate 후 진행한다.
 * </p>
 */
package com.ticketing.common;
