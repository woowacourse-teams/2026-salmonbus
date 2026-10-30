package com.gustler.backend.api.http.login;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.account.analytics.AmplitudeDeletionProcessor;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.WithdrawMember;
import com.gustler.backend.member.api.WithdrawnMember;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppleWithdrawalTest {
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final MemberIdentity IDENTITY = new MemberIdentity(LoginProvider.APPLE, "001234.apple.member");

    private final WithdrawMember withdrawMember = mock(WithdrawMember.class);
    private final AppleRevokeClient revokeClient = mock(AppleRevokeClient.class);
    private final AmplitudeDeletionProcessor analytics = mock(AmplitudeDeletionProcessor.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AppleWithdrawal withdrawal = new AppleWithdrawal(withdrawMember, revokeClient, meters, analytics);

    @BeforeEach
    void 회원은_같은_애플_계정이다() {
        when(withdrawMember.identityOf(MEMBER_ID)).thenReturn(Optional.of(IDENTITY));
    }

    @Test
    void 토큰을_취소하면_회원을_지운다() {
        // given
        when(revokeClient.revoke("token")).thenReturn(true);
        when(withdrawMember.withdraw(MEMBER_ID, ProviderUnlink.DONE))
            .thenReturn(Optional.of(new WithdrawnMember(IDENTITY, Optional.empty())));

        // when
        boolean actual = withdrawal.withdraw(MEMBER_ID, IDENTITY, Optional.of("token"));

        // then
        verify(withdrawMember).withdraw(MEMBER_ID, ProviderUnlink.DONE);
        verify(analytics).attempt(MEMBER_ID);
        assertSoftly(softly -> {
            softly.assertThat(actual).isTrue();
            softly.assertThat(count("done")).isEqualTo(1);
        });
    }

    @Test
    void 토큰_취소에_실패하면_회원을_지우지_않는다() {
        // given
        when(revokeClient.revoke("token")).thenReturn(false);

        // when
        boolean actual = withdrawal.withdraw(MEMBER_ID, IDENTITY, Optional.of("token"));

        // then
        verify(withdrawMember, never()).withdraw(any(), any());
        verify(analytics, never()).attempt(any());
        assertSoftly(softly -> {
            softly.assertThat(actual).isFalse();
            softly.assertThat(count("revoke_failed")).isEqualTo(1);
        });
    }

    @Test
    void 취소할_토큰이_없으면_애플을_부르지_않고_회원을_지우지_않는다() {
        // when
        boolean actual = withdrawal.withdraw(MEMBER_ID, IDENTITY, Optional.empty());

        // then
        verify(revokeClient, never()).revoke(anyString());
        verify(withdrawMember, never()).withdraw(any(), any());
        verify(analytics, never()).attempt(any());
        assertSoftly(softly -> {
            softly.assertThat(actual).isFalse();
            softly.assertThat(count("revoke_failed")).isEqualTo(1);
        });
    }

    @Test
    void 다시_인증한_계정이_다르면_토큰을_취소하지_않고_회원을_지우지_않는다() {
        // when
        boolean actual = withdrawal.withdraw(
            MEMBER_ID, new MemberIdentity(LoginProvider.APPLE, "009999.apple.someone"), Optional.of("token"));

        // then
        verify(revokeClient, never()).revoke(anyString());
        verify(withdrawMember, never()).withdraw(any(), any());
        verify(analytics, never()).attempt(any());
        assertSoftly(softly -> {
            softly.assertThat(actual).isFalse();
            softly.assertThat(count("identity_mismatch")).isEqualTo(1);
        });
    }

    private double count(String result) {
        return meters.counter("auth.withdraw", "provider", "APPLE", "result", result).count();
    }
}
