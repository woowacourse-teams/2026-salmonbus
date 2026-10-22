package com.gustler.backend.member.domain;

import java.time.Duration;
import java.time.OffsetDateTime;

/** 탈퇴한 회원의 제공자 연결 해제를 다시 시도하는 규칙. */
public final class ProviderUnlinkPolicy {
    /**
     * 요청 하나를 시도하는 쪽이 독점하는 시간이다. 이 시간 동안은 다른 서버의 주기 작업이 같은 요청을 집어 가지 않는다.
     * 제공자 API 를 한 번 호출하는 시간 제한보다 넉넉히 길어야 한다. 시도하던 서버가 결과를 알리지 못하고 죽으면 이 시간이
     * 지난 뒤 다른 서버가 이어받으므로, 길게 잡을수록 그런 요청의 해제가 늦어진다.
     */
    public static final Duration LEASE = Duration.ofMinutes(5);

    /**
     * 이만큼 실패하면 더 시도하지 않는다. 백오프를 따르면 첫 실패부터 마지막 시도까지 8시간 반쯤 걸려 제공자 장애가 몇 시간
     * 이어져도 버틴다. 그보다 오래 실패하면 일시 장애가 아니라 사람이 봐야 할 문제로 본다.
     */
    public static final int MAX_ATTEMPTS = 10;

    /** 첫 실패 뒤 기다리는 시간이다. 실패할 때마다 두 배가 된다. 일시적인 네트워크 오류는 곧 다시 시도해 빨리 끝낸다. */
    public static final Duration INITIAL_BACKOFF = Duration.ofMinutes(1);

    /**
     * 기다리는 시간의 상한이다. 지금 횟수 상한에서는 닿지 않지만, 횟수 상한을 늘려도 대기가 끝없이 길어지지 않게 둔다.
     */
    public static final Duration MAX_BACKOFF = Duration.ofHours(6);

    /** provider_unlink.last_error 열의 글자 수 상한이다. 원인은 코드포인트 기준으로 이 길이에서 자른다. */
    public static final int MAX_ERROR_LENGTH = 200;

    /**
     * 포기한 요청을 남겨 두는 기간이다. 사람이 확인하고 직접 해제할 시간을 주되, 탈퇴한 회원의 제공자 식별자를 기한 없이
     * 보관하지 않기 위해 둔다.
     */
    public static final Duration GAVE_UP_RETENTION = Duration.ofDays(30);

    /** 실패 원인을 알리지 않았을 때 남기는 문구. */
    public static final String UNKNOWN_ERROR = "원인을 알리지 않았다";

    private static final int MAX_SHIFT = 30;

    private ProviderUnlinkPolicy() {
    }

    /** {@code previousFailures} 번 실패한 요청이 한 번 더 실패한 뒤의 상태. 횟수 상한에 닿으면 포기한다. */
    public static UnlinkFailure afterFailure(int previousFailures, OffsetDateTime now) {
        final int attempts = previousFailures + 1;
        if (attempts >= MAX_ATTEMPTS) {
            return new UnlinkFailure(attempts, UnlinkStatus.GAVE_UP, now);
        }
        return new UnlinkFailure(attempts, UnlinkStatus.PENDING, now.plus(backoff(previousFailures)));
    }

    /** {@code previousFailures} 번 실패한 요청이 이번에 실패하면 기다릴 시간. {@code 1분 × 2^previousFailures} 에 상한을 둔다. */
    public static Duration backoff(int previousFailures) {
        Duration doubled = INITIAL_BACKOFF.multipliedBy(1L << Math.min(previousFailures, MAX_SHIFT));
        if (doubled.compareTo(MAX_BACKOFF) > 0) {
            return MAX_BACKOFF;
        }
        return doubled;
    }

    /** 저장할 실패 원인. 비어 있으면 고정 문구이고, 길면 {@link #MAX_ERROR_LENGTH} 글자에서 자른다. */
    public static String errorOf(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN_ERROR;
        }
        return reason.strip().codePoints()
            .limit(MAX_ERROR_LENGTH)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }
}
