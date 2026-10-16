package com.ticketing.show.api.dto;

/**
 * 주최자 입력의 공통 한도. 화면(frontend/app/organizer)도 같은 값을 쓴다.
 *
 * <p>
 *   2026-10-13 QA 에서 실측한 문제를 막는다 — 기준가 Long 최대값이 들어가 그 회차 가격 계산이
 *   매초 실패, 포스터 주소에 {@code javascript:}·{@code data:} 허용, PATCH 로 제목을 빈 값으로 바꿈,
 *   설명 1MB 허용.
 * </p>
 */
public final class InputRules {

    private InputRules() {
    }

    /** 기준가 상한(원). 천장 배수(최대 3배)를 곱해도 long 범위에 한참 못 미친다. */
    public static final long MAX_BASE_PRICE = 100_000_000L;

    public static final int MAX_DESCRIPTION = 2000;

    public static final int MAX_POSTER_URL = 500;

    /**
     * 사이트 안 경로(`/posters/x.svg`) 또는 https 주소만. `//evil` 같은 스킴 생략 주소는 막는다.
     * 빈 문자열은 수정 때 "포스터 지우기" 로 쓴다.
     */
    public static final String POSTER_URL = "^$|^(/(?!/)\\S*|https://\\S+)$";

    /** 값이 있으면 공백만으로 이루어지면 안 된다 (PATCH 의 선택 필드용). null 은 검사하지 않는다. */
    public static final String NOT_BLANK_IF_PRESENT = "(?s).*\\S.*";
}
