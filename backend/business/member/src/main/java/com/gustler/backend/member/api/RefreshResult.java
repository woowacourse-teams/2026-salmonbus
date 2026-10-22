package com.gustler.backend.member.api;

import java.util.UUID;

public sealed interface RefreshResult {
    /** 갱신에 성공했다. 사용한 토큰은 더 이상 쓸 수 없고 {@code next} 로 이어서 갱신한다. */
    record Rotated(UUID publicId, IssuedRefreshToken next) implements RefreshResult {
    }

    /** 갱신할 수 없다. 이유는 서버 로그와 분석에만 쓰고 클라이언트에는 구분해 알리지 않는다. */
    record Rejected(Reason reason) implements RefreshResult {
    }

    enum Reason {
        UNKNOWN,
        EXPIRED,
        /** 이미 쓴 토큰이 유예 시간이 지난 뒤 다시 왔다. 탈취로 보고 같은 로그인 계통의 토큰을 모두 폐기했다. */
        REUSED,
    }
}
