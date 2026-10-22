package com.gustler.backend.api.account.unlink;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlinks;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProviderUnlinkRetryJobTest {
    private final ProviderUnlinks unlinks = mock(ProviderUnlinks.class);
    private final ProviderUnlinkRunner runner = mock(ProviderUnlinkRunner.class);
    private final ProviderUnlinkRetryJob job = new ProviderUnlinkRetryJob(unlinks, runner);

    @Test
    void 잡은_요청을_모두_시도하고_묶음이_덜_차면_멈춘다() {
        // given
        when(unlinks.claimDue(ProviderUnlinkRetryJob.BATCH_SIZE))
            .thenReturn(pending(ProviderUnlinkRetryJob.BATCH_SIZE), pending(3));

        // when
        job.retry();

        // then
        verify(unlinks, times(2)).claimDue(ProviderUnlinkRetryJob.BATCH_SIZE);
        verify(runner, times(ProviderUnlinkRetryJob.BATCH_SIZE + 3)).attempt(any());
    }

    @Test
    void 한_번의_실행에서_잡는_묶음_수에_상한이_있다() {
        // given
        when(unlinks.claimDue(ProviderUnlinkRetryJob.BATCH_SIZE))
            .thenReturn(pending(ProviderUnlinkRetryJob.BATCH_SIZE));

        // when
        job.retry();

        // then
        verify(unlinks, times(ProviderUnlinkRetryJob.MAX_BATCHES_PER_RUN))
            .claimDue(ProviderUnlinkRetryJob.BATCH_SIZE);
    }

    @Test
    void 잡을_요청이_없으면_한_번만_묻는다() {
        // given
        when(unlinks.claimDue(ProviderUnlinkRetryJob.BATCH_SIZE)).thenReturn(Collections.emptyList());

        // when
        job.retry();

        // then
        verify(unlinks, times(1)).claimDue(ProviderUnlinkRetryJob.BATCH_SIZE);
    }

    @Test
    void 포기한_요청은_묶음이_덜_찰_때까지_지운다() {
        // given
        when(unlinks.purgeGaveUp()).thenReturn(ProviderUnlinks.PURGE_BATCH_SIZE, 2);

        // when
        job.purgeGaveUp();

        // then
        verify(unlinks, times(2)).purgeGaveUp();
    }

    private static List<PendingUnlink> pending(int size) {
        return IntStream.range(0, size)
            .mapToObj(index -> new PendingUnlink(index, new MemberIdentity(LoginProvider.KAKAO, "s" + index), 0))
            .toList();
    }
}
