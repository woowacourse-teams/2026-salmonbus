package com.gustler.backend.api.http.login;

/** 애플이 서버 간 알림으로 알린 애플 계정의 변화. {@code subject} 는 애플 계정 식별자({@code sub})다. */
sealed interface AppleAccountEvent {
    record AccountDeleted(String subject) implements AppleAccountEvent {
    }

    /** 이용자가 애플 설정에서 우리 서비스와의 연결을 끊었다. */
    record ConsentRevoked(String subject) implements AppleAccountEvent {
    }

    /** 처리하지 않는 알림. 이메일 전달 설정 변경과 모르는 종류다. */
    record Ignored(String type) implements AppleAccountEvent {
    }

    /** {@code account-delete} 는 애플 문서의 옛 표기다. */
    static AppleAccountEvent of(String type, String subject) {
        return switch (type) {
            case "account-deleted", "account-delete" -> new AccountDeleted(subject);
            case "consent-revoked" -> new ConsentRevoked(subject);
            default -> new Ignored(type);
        };
    }
}
