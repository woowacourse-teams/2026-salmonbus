package com.gustler.backend.api.account.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gustler.backend.member.api.AnalyticsDeletions;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class AmplitudeDeletionProcessorTest {
    private final AnalyticsDeletions deletions = mock(AnalyticsDeletions.class);
    private final AmplitudeDeletionClient client = mock(AmplitudeDeletionClient.class);
    private final AmplitudeDeletionProcessor job = new AmplitudeDeletionProcessor(deletions,
        new StaticListableBeanFactory(Map.of("client", client)).getBeanProvider(AmplitudeDeletionClient.class));

    @Test
    void 탈퇴한_회원의_공개_ID로_즉시_시도한다() {
        UUID id = UUID.randomUUID();
        job.attempt(id);
        verify(deletions).attempt(eq(id), any());
    }

    @Test
    void 결과_저장에_실패해도_탈퇴_응답에는_예외를_보내지_않는다() {
        doThrow(new IllegalStateException()).when(deletions).attempt(any(), any());
        assertThatCode(() -> job.attempt(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void 주기_작업이_남은_요청을_다시_시도한다() {
        job.retry();
        verify(deletions).retry(any());
    }

    @Test
    void 전송을_꺼두면_대기_기록을_처리하지_않는다() {
        AmplitudeDeletionProcessor disabled = new AmplitudeDeletionProcessor(deletions,
            new StaticListableBeanFactory().getBeanProvider(AmplitudeDeletionClient.class));
        disabled.attempt(UUID.randomUUID());
        disabled.retry();
        verifyNoInteractions(deletions);
    }
}
