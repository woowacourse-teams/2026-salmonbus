package com.gustler.backend.member.domain;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 리프레시 토큰 계통 저장소. 계통마다 한 행이다. */
public interface RefreshTokenRepository {
    /** 세대 0 의 새 계통을 저장한다. 회원이 없으면 아무것도 저장하지 않고 {@code false} 다. */
    boolean startFamily(
        UUID publicId,
        UUID familyId,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    );

    /**
     * 현재 세대가 {@code generation} 이고 만료 전인 계통의 세대를 한 문장으로 하나 올린다.
     *
     * <p>같은 세대를 동시에 올리려 하면 한 호출만 값을 얻는다. 나머지는 먼저 올린 쪽이 끝나기를 기다렸다가
     * 조건을 다시 평가해 빈 값을 받는다.
     */
    Optional<RotatedRefreshToken> rotate(
        UUID familyId,
        int generation,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    );

    Optional<RefreshTokenFamily> find(UUID familyId);

    /** 계통을 지운다. 없으면 아무 일도 하지 않는다. */
    void delete(UUID familyId);

    /** {@code now} 보다 먼저 만료된 계통을 {@code limit} 개까지 지우고 지운 개수를 돌려준다. */
    int deleteExpired(OffsetDateTime now, int limit);
}
