package com.gustler.backend.api.docs;

/** 로그인 API 공개 문서용 합성 예시. contract/auth-v1.openapi.json 의 같은 이름 예시와 문서 테스트에서 대조한다. */
public final class AuthApiDocsExamples {
    public static final String SESSION_AUTHENTICATED = """
        {
          "authenticated": true,
          "refreshable": false,
          "member": {
            "id": "00000000-0000-4000-8000-000000000001",
            "nickname": "졸린판교역42",
            "provider": "KAKAO"
          }
        }
        """;

    public static final String SESSION_REFRESHABLE = """
        {"authenticated":false,"refreshable":true}
        """;

    public static final String SESSION_ANONYMOUS = """
        {"authenticated":false,"refreshable":false}
        """;

    public static final String TOKEN = """
        {
          "accessToken": "example-access-token",
          "expiresIn": 900,
          "refreshToken": "example-refresh-token",
          "refreshTokenExpiresAt": "2026-11-01T12:00:00+09:00"
        }
        """;

    public static final String PROFILE = """
        {
          "id": "00000000-0000-4000-8000-000000000001",
          "nickname": "졸린판교역42",
          "provider": "KAKAO",
          "createdAt": "2026-10-01T12:00:00+09:00"
        }
        """;

    public static final String APPLE_WITHDRAWAL = """
        {"startUrl":"/api/auth/apple/start?intent=withdraw&ticket=example-ticket&returnTo=%2Froutes%2F123456789"}
        """;

    public static final String UNAUTHENTICATED = """
        {"code":"UNAUTHENTICATED","message":"로그인이 필요합니다.","requestId":"example-request"}
        """;

    public static final String CSRF_REJECTED = """
        {"code":"CSRF_REJECTED","message":"요청을 확인하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.",\
        "requestId":"example-request"}
        """;

    public static final String NICKNAME_LENGTH = """
        {"code":"NICKNAME_LENGTH","message":"닉네임은 2자 이상 10자 이하여야 합니다.","requestId":"example-request"}
        """;

    public static final String NICKNAME_CHARACTERS = """
        {"code":"NICKNAME_CHARACTERS","message":"닉네임은 한글, 영문, 숫자만 쓸 수 있습니다.","requestId":"example-request"}
        """;

    public static final String NICKNAME_RESERVED = """
        {"code":"NICKNAME_RESERVED","message":"쓸 수 없는 닉네임입니다.","requestId":"example-request"}
        """;

    public static final String NICKNAME_TAKEN = """
        {"code":"NICKNAME_TAKEN","message":"이미 사용 중인 닉네임입니다.","requestId":"example-request"}
        """;

    public static final String REAUTH_REQUIRED = """
        {"code":"REAUTH_REQUIRED","message":"애플 계정으로 한 번 더 인증해야 탈퇴할 수 있습니다.",\
        "requestId":"example-request"}
        """;

    public static final String APP_LOGIN_NONCE = """
        {"nonce":"example-nonce"}
        """;

    public static final String ENDPOINT_NOT_FOUND = """
        {"code":"ENDPOINT_NOT_FOUND","message":"요청한 경로를 찾을 수 없습니다.","requestId":"example-request"}
        """;

    public static final String INVALID_PUSH_DEVICE = """
        {"code":"INVALID_PUSH_DEVICE","message":"푸시 기기 정보가 올바르지 않습니다.","requestId":"example-request"}
        """;

    public static final String INVALID_NOTIFICATION_SCHEDULE = """
        {"code":"INVALID_NOTIFICATION_SCHEDULE","message":"알림 시각과 요일이 올바르지 않습니다.",        "requestId":"example-request"}
        """;

    public static final String NOTIFICATION_TARGET_NOT_FAVORITE = """
        {"code":"NOTIFICATION_TARGET_NOT_FAVORITE","message":"즐겨찾기에 없는 대상입니다.",        "requestId":"example-request"}
        """;

    public static final String NOTIFICATION_SETTING_NOT_FOUND = """
        {"code":"NOTIFICATION_SETTING_NOT_FOUND","message":"알림 설정을 찾을 수 없습니다.",        "requestId":"example-request"}
        """;

    private AuthApiDocsExamples() {
    }
}
