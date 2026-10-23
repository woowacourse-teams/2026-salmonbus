package com.gustler.backend.api.account.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.member.api.RefreshTokens;
import org.junit.jupiter.api.Test;

class RefreshTokenPurgeTest {
    private final RefreshTokens refreshTokens = mock(RefreshTokens.class);
    private final RefreshTokenPurge purge = new RefreshTokenPurge(refreshTokens);

    @Test
    void 상한만큼_지웠으면_이어서_부르고_적게_지우면_멈춘다() {
        // given
        when(refreshTokens.purgeObsolete())
            .thenReturn(RefreshTokens.PURGE_BATCH_SIZE, RefreshTokens.PURGE_BATCH_SIZE, 7);

        // when
        purge.purge();

        // then
        verify(refreshTokens, times(3)).purgeObsolete();
    }

    @Test
    void 지울_게_없으면_한_번만_부른다() {
        // given
        when(refreshTokens.purgeObsolete()).thenReturn(0);

        // when
        purge.purge();

        // then
        verify(refreshTokens, times(1)).purgeObsolete();
    }

    @Test
    void 한_번의_실행에서_부르는_횟수에_상한이_있다() {
        // given
        when(refreshTokens.purgeObsolete()).thenReturn(RefreshTokens.PURGE_BATCH_SIZE);

        // when
        purge.purge();

        // then
        verify(refreshTokens, times(RefreshTokenPurge.MAX_BATCHES_PER_RUN)).purgeObsolete();
        assertThat(RefreshTokenPurge.MAX_BATCHES_PER_RUN).isPositive();
    }
}
