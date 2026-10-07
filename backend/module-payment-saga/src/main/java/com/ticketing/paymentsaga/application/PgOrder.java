package com.ticketing.paymentsaga.application;

/** Mock PG 조회/취소 응답. NOT_FOUND는 재청구 사유가 아니라 취소 tombstone 생성 대상이다. */
public record PgOrder(String status, String approveNo, String code, String message) { }
