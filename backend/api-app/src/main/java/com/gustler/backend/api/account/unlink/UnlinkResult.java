package com.gustler.backend.api.account.unlink;

/**
 * 제공자 연결을 끊으려 한 결과.
 *
 * <p>{@code alreadyUnlinked} 는 제공자가 "이미 연결되지 않은 사용자"라고 답한 경우다. 끊긴 것으로 보지만 따로 센다. 어드민
 * 키를 다른 앱의 것으로 잘못 넣어도 같은 답이 오므로, 이 값이 늘면 설정을 의심할 수 있어야 한다. {@code reason} 은 실패했을 때
 * 사람이 읽는 짧은 원인이며 키나 토큰을 담지 않는다.
 */
public record UnlinkResult(boolean unlinked, boolean alreadyUnlinked, String reason) {
    static UnlinkResult done() {
        return new UnlinkResult(true, false, null);
    }

    static UnlinkResult notLinked() {
        return new UnlinkResult(true, true, null);
    }

    static UnlinkResult failed(String reason) {
        return new UnlinkResult(false, false, reason);
    }
}
