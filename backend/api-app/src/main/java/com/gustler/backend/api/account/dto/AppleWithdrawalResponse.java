package com.gustler.backend.api.account.dto;

/** 브라우저가 이동할 애플 재인증 탈퇴 시작 주소. 안에 든 티켓은 5분 동안만 유효하다. */
public record AppleWithdrawalResponse(String startUrl) {
}
