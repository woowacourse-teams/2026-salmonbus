package com.gustler.backend.api.account.dto;

/** 쿠키를 쓰지 않는 클라이언트가 리프레시 토큰을 본문으로 보낸다. 비어 있으면 쿠키를 본다. */
public record RefreshRequest(String refreshToken) {
}
