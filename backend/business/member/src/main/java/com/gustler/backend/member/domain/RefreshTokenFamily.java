package com.gustler.backend.member.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 저장된 로그인 계통. 현재 세대와 그 세대를 발급한 시각, 만료 시각만 가진다.
 */
public record RefreshTokenFamily(
    UUID familyId,
    UUID publicId,
    int generation,
    OffsetDateTime rotatedAt,
    OffsetDateTime expiresAt
) {

    /** 만료 시각 정각부터 만료다. 만료가 가장 먼저 판정된다. */
    public RefreshTokenStatus statusOf(int presentedGeneration, OffsetDateTime now) {
        if (!expiresAt.isAfter(now)) {
            return RefreshTokenStatus.EXPIRED;
        }
        if (presentedGeneration == generation) {
            return RefreshTokenStatus.CURRENT;
        }
        if (presentedGeneration > generation) {
            return RefreshTokenStatus.FUTURE;
        }
        if (presentedGeneration == generation - 1 && !rotatedAt.plus(RefreshTokenPolicy.GRACE).isBefore(now)) {
            return RefreshTokenStatus.IN_GRACE;
        }
        return RefreshTokenStatus.REUSED;
    }
}
