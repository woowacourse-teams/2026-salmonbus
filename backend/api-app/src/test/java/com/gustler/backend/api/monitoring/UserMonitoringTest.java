package com.gustler.backend.api.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.monitoring.UserMonitoring.LoginResult;
import com.gustler.backend.api.monitoring.UserMonitoring.MemberKind;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberRegistrationEvents.Failure;
import com.gustler.backend.member.api.MemberStatistics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class UserMonitoringTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T01:23:45Z"), ZoneOffset.UTC);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableStatistics statistics = new MutableStatistics();
    private final UserMonitoring monitoring = new UserMonitoring(meters, statistics, CLOCK);

    @Test
    void 가능한_가입과_로그인_counter_조합을_0으로_미리_등록한다() {
        for (LoginProvider provider : LoginProvider.values()) {
            String providerTag = provider.name().toLowerCase(Locale.ROOT);
            assertThat(meters.get("salmonbus.user.registration")
                .tags("provider", providerTag, "result", "success").counter().count()).isZero();
            for (Failure failure : Failure.values()) {
                String resultTag = failure.name().toLowerCase(Locale.ROOT);
                assertThat(meters.get("salmonbus.user.registration")
                    .tags("provider", providerTag, "result", resultTag).counter().count()).isZero();
            }
            assertLoginCountersStartAtZero(providerTag);
        }
        assertLoginCountersStartAtZero("unknown");
    }

    @Test
    void 정해진_값만_가입과_로그인_metric_tag로_등록한다() {
        // when
        monitoring.succeeded(LoginProvider.KAKAO);
        monitoring.failed(LoginProvider.APPLE, Failure.NICKNAME_EXHAUSTED);
        monitoring.login(LoginProvider.KAKAO, LoginResult.SUCCESS, MemberKind.NEW);
        monitoring.login(java.util.Optional.empty(), LoginResult.FAILED, MemberKind.UNKNOWN);

        // then
        assertThat(meters.get("salmonbus.user.registration")
            .tags("provider", "kakao", "result", "success").counter().count()).isEqualTo(1);
        assertThat(meters.get("salmonbus.user.registration")
            .tags("provider", "apple", "result", "nickname_exhausted").counter().count()).isEqualTo(1);
        assertThat(meters.get("salmonbus.user.login")
            .tags("provider", "kakao", "result", "success", "member", "new").counter().count()).isEqualTo(1);
        assertThat(meters.get("salmonbus.user.login")
            .tags("provider", "unknown", "result", "failed", "member", "unknown").counter().count()).isEqualTo(1);
    }

    @Test
    void 현재_회원수와_마지막_갱신_시각을_메모리에_보관한다() {
        // given
        statistics.currentMembers = 23;

        // when
        monitoring.refreshCurrentMembers();

        // then
        assertThat(meters.get("salmonbus.users.current").gauge().value()).isEqualTo(23);
        assertThat(meters.get("salmonbus.users.snapshot.timestamp").gauge().value())
            .isEqualTo(CLOCK.instant().getEpochSecond());
    }

    @Test
    void 회원수_조회가_실패하면_기존_표본을_유지한다(CapturedOutput output) {
        // given
        statistics.currentMembers = 23;
        monitoring.refreshCurrentMembers();
        statistics.failure = new IllegalStateException("DB URL과 비밀번호를 로그에 남기면 안 됨");

        // when
        monitoring.refreshCurrentMembers();

        // then
        assertThat(meters.get("salmonbus.users.current").gauge().value()).isEqualTo(23);
        assertThat(meters.get("salmonbus.users.snapshot.timestamp").gauge().value())
            .isEqualTo(CLOCK.instant().getEpochSecond());
        assertThat(output.getAll())
            .contains("event=user_count_refresh status=FAILED cause=IllegalStateException")
            .doesNotContain("DB URL과 비밀번호");
    }

    private static final class MutableStatistics implements MemberStatistics {
        private long currentMembers;
        private RuntimeException failure;

        @Override
        public long currentMembers() {
            if (failure != null) {
                throw failure;
            }
            return currentMembers;
        }
    }

    private void assertLoginCountersStartAtZero(String provider) {
        for (LoginResult result : LoginResult.values()) {
            for (MemberKind member : MemberKind.values()) {
                assertThat(meters.get("salmonbus.user.login")
                    .tags(
                        "provider", provider,
                        "result", result.name().toLowerCase(Locale.ROOT),
                        "member", member.name().toLowerCase(Locale.ROOT)
                    )
                    .counter()
                    .count()).isZero();
            }
        }
    }
}
