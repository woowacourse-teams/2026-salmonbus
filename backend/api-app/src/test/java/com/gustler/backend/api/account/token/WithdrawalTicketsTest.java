package com.gustler.backend.api.account.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WithdrawalTicketsTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-10-22T00:00:00Z");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final TokenProperties PROPERTIES = properties("unit-test-secret-key-unit-test-secret-key");

    @Test
    void 발급한_티켓은_유효_시간_안에는_회원_ID로_검증된다() {
        // given
        String ticket = ticketsAt(ISSUED_AT).issue(MEMBER);

        // when
        Optional<UUID> actual = ticketsAt(ISSUED_AT.plus(Duration.ofMinutes(4))).verify(ticket);

        // then
        assertThat(actual).contains(MEMBER);
    }

    @Test
    void 유효_시간이_지나면_검증에_실패한다() {
        // given
        String ticket = ticketsAt(ISSUED_AT).issue(MEMBER);

        // when
        Optional<UUID> actual = ticketsAt(ISSUED_AT.plus(WithdrawalTickets.VALIDITY)).verify(ticket);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 회원_ID나_만료_시각이나_서명을_바꾸면_검증에_실패한다() {
        // given
        String[] parts = ticketsAt(ISSUED_AT).issue(MEMBER).split("\\.");
        String otherMember = ticketsAt(ISSUED_AT).issue(UUID.randomUUID()).split("\\.")[0];
        WithdrawalTickets tickets = ticketsAt(ISSUED_AT);
        String laterExpiry = parts[0] + "." + (Long.parseLong(parts[1]) + 60) + "." + parts[2];

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(tickets.verify(otherMember + "." + parts[1] + "." + parts[2])).isEmpty();
            softly.assertThat(tickets.verify(laterExpiry)).isEmpty();
            softly.assertThat(tickets.verify(parts[0] + "." + parts[1] + "." + "A".repeat(43))).isEmpty();
        });
    }

    @Test
    void 다른_비밀로_만든_티켓은_검증에_실패한다() {
        // given
        String ticket = new WithdrawalTickets(properties("another-secret-key-another-secret-key-00"), fixed(ISSUED_AT))
            .issue(MEMBER);

        // when
        Optional<UUID> actual = ticketsAt(ISSUED_AT).verify(ticket);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 액세스_토큰은_탈퇴_티켓으로_통하지_않는다() {
        // given
        String accessToken = new AccessTokens(PROPERTIES, fixed(ISSUED_AT)).issue(MEMBER);

        // when
        Optional<UUID> actual = ticketsAt(ISSUED_AT).verify(accessToken);

        // then
        assertThat(actual).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "garbage", "a.b.c", "....", "AAAAAAAAAAAAAAAAAAAAAA.9999999999999.AAAA"})
    void 형식이_깨진_티켓은_예외_없이_실패한다(String ticket) {
        // when
        Optional<UUID> actual = ticketsAt(ISSUED_AT).verify(ticket);

        // then
        assertThat(actual).isEmpty();
    }

    private static WithdrawalTickets ticketsAt(Instant instant) {
        return new WithdrawalTickets(PROPERTIES, fixed(instant));
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static TokenProperties properties(String secret) {
        return new TokenProperties(secret, "k1", Duration.ofMinutes(15));
    }
}
