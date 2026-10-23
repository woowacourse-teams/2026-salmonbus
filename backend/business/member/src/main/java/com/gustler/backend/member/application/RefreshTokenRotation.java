package com.gustler.backend.member.application;

import com.gustler.backend.member.api.IssuedRefreshToken;
import com.gustler.backend.member.api.RefreshResult;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.domain.RefreshTokenClaim;
import com.gustler.backend.member.domain.RefreshTokenCodec;
import com.gustler.backend.member.domain.RefreshTokenFamily;
import com.gustler.backend.member.domain.RefreshTokenPolicy;
import com.gustler.backend.member.domain.RefreshTokenRepository;
import com.gustler.backend.member.domain.RotatedRefreshToken;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서명된 리프레시 토큰을 한 세대씩 올리며 갱신한다.
 *
 * <p>계통의 현재 세대를 올리는 한 문장을 이긴 요청만 새 토큰을 받는다. 같은 토큰으로 동시에 온 나머지 요청은 이긴 요청이
 * 끝난 뒤 한 세대 전 토큰으로 읽혀 유예 규칙을 따르고, 상태를 바꾸지 않은 채 이긴 요청과 같은 토큰을 다시 계산해 받는다.
 * 토큰은 (계통, 세대)로 결정되기 때문이다.
 *
 * <p>토큰 원문은 반환값에만 담고 로그와 예외 메시지에는 쓰지 않는다.
 */
@Component
@Transactional
public class RefreshTokenRotation implements RefreshTokens {
    private final RefreshTokenRepository repository;
    private final RefreshTokenCodec codec;
    private final Clock clock;

    public RefreshTokenRotation(RefreshTokenRepository repository, RefreshTokenCodec codec, Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public IssuedRefreshToken issue(UUID publicId) {
        OffsetDateTime now = now();
        OffsetDateTime expiresAt = now.plus(RefreshTokenPolicy.LIFETIME);
        UUID familyId = UUID.randomUUID();
        if (!repository.startFamily(publicId, familyId, now, expiresAt)) {
            throw new IllegalArgumentException("리프레시 토큰을 발급할 회원이 없다");
        }
        return new IssuedRefreshToken(codec.encode(familyId, 0), expiresAt);
    }

    @Override
    public RefreshResult rotate(String token) {
        Optional<RefreshTokenClaim> claim = codec.decode(token);
        if (claim.isEmpty()) {
            return rejected(RefreshResult.Reason.UNKNOWN);
        }
        RefreshTokenClaim presented = claim.get();
        OffsetDateTime now = now();
        Optional<RotatedRefreshToken> rotated = repository.rotate(presented.familyId(), presented.generation(), now,
            now.plus(RefreshTokenPolicy.LIFETIME), RefreshTokenPolicy.MAX_LIFETIME);
        if (rotated.isPresent()) {
            return new RefreshResult.Rotated(rotated.get().publicId(),
                new IssuedRefreshToken(codec.encode(presented.familyId(), rotated.get().generation()),
                    rotated.get().expiresAt()));
        }
        return repository.find(presented.familyId())
            .map(family -> rotateStale(family, presented, now))
            .orElseGet(() -> rejected(RefreshResult.Reason.UNKNOWN));
    }

    @Override
    public void revoke(String token) {
        codec.decode(token).ifPresent(claim -> repository.delete(claim.familyId()));
    }

    @Override
    public int revokeAll(UUID publicId) {
        return repository.deleteAllOf(publicId);
    }

    @Override
    public int purgeObsolete() {
        return repository.deleteExpired(now(), PURGE_BATCH_SIZE);
    }

    private RefreshResult rotateStale(RefreshTokenFamily family, RefreshTokenClaim presented, OffsetDateTime now) {
        return switch (family.statusOf(presented.generation(), now)) {
            case IN_GRACE -> new RefreshResult.Rotated(family.publicId(),
                new IssuedRefreshToken(codec.encode(family.familyId(), family.generation()), family.expiresAt()));
            case REUSED -> revokeFamily(family, RefreshResult.Reason.REUSED);
            case EXPIRED -> revokeFamily(family, RefreshResult.Reason.EXPIRED);
            case FUTURE -> rejected(RefreshResult.Reason.UNKNOWN);
            case CURRENT -> throw new IllegalStateException("세대 올리기에 실패한 토큰이 현재 세대다");
        };
    }

    private RefreshResult revokeFamily(RefreshTokenFamily family, RefreshResult.Reason reason) {
        repository.delete(family.familyId());
        return rejected(reason);
    }

    private static RefreshResult rejected(RefreshResult.Reason reason) {
        return new RefreshResult.Rejected(reason);
    }

    /** PostgreSQL 의 {@code timestamptz} 정밀도에 맞춰 반환값과 저장값이 같게 한다. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
