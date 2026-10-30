package com.gustler.backend.api.account.dto;

/** 애플이 서버 간 알림으로 보내는 본문. {@code payload} 는 애플이 서명한 JWT 다. */
public record AppleNotificationRequest(String payload) {
}
