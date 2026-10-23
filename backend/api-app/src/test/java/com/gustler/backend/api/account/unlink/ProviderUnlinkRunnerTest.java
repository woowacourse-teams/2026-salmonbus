package com.gustler.backend.api.account.unlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlinks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

class ProviderUnlinkRunnerTest {
    private static final PendingUnlink KAKAO = new PendingUnlink(7L, new MemberIdentity(LoginProvider.KAKAO, "42"), 0);
    private static final PendingUnlink APPLE = new PendingUnlink(8L, new MemberIdentity(LoginProvider.APPLE, "a"), 0);

    private final ProviderUnlinks unlinks = mock(ProviderUnlinks.class);
    private final KakaoUnlinkClient kakao = mock(KakaoUnlinkClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void 해제에_성공하면_요청을_지운다() {
        // given
        when(kakao.unlink("42")).thenReturn(UnlinkResult.done());

        // when
        runner(kakao).attempt(KAKAO);

        // then
        verify(unlinks).succeeded(7L);
        verify(unlinks, never()).failed(anyLong(), anyString());
    }

    @Test
    void 제공자가_응답하지_않아도_예외를_내보내지_않고_실패로_기록한다() {
        // given
        when(kakao.unlink("42")).thenThrow(new ResourceAccessException("timeout"));

        // when
        runner(kakao).attempt(KAKAO);

        // then
        verify(unlinks).failed(7L, "ResourceAccessException");
        assertThat(meters.counter("auth.unlink", "provider", "KAKAO", "result", "failed").count()).isEqualTo(1);
    }

    @Test
    void 결과를_기록하지_못해도_예외를_내보내지_않는다() {
        // given
        when(kakao.unlink("42")).thenReturn(UnlinkResult.done());
        doThrow(new IllegalStateException("DB 장애")).when(unlinks).succeeded(7L);

        // when
        runner(kakao).attempt(KAKAO);

        // then
        verify(unlinks).succeeded(7L);
    }

    @Test
    void 이미_연결되지_않은_사용자는_해제로_보되_따로_센다() {
        // given
        when(kakao.unlink("42")).thenReturn(UnlinkResult.notLinked());

        // when
        runner(kakao).attempt(KAKAO);

        // then
        verify(unlinks).succeeded(7L);
        assertThat(meters.counter("auth.unlink", "provider", "KAKAO", "result", "not_linked").count()).isEqualTo(1);
    }

    @Test
    void 카카오_로그인이_꺼져_있으면_실패로_기록한다() {
        // when
        runner(null).attempt(KAKAO);

        // then
        verify(unlinks).failed(eq(7L), anyString());
    }

    @Test
    void 애플_요청은_서버가_해제할_수단이_없어_실패로_기록한다() {
        // when
        runner(kakao).attempt(APPLE);

        // then
        verify(unlinks).failed(eq(8L), anyString());
        verify(kakao, never()).unlink(anyString());
    }

    @SuppressWarnings("unchecked")
    private ProviderUnlinkRunner runner(KakaoUnlinkClient client) {
        ObjectProvider<KakaoUnlinkClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new ProviderUnlinkRunner(unlinks, provider, meters);
    }
}
