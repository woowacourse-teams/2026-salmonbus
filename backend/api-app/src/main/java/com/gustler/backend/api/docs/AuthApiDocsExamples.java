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

    private AuthApiDocsExamples() {
    }
}
