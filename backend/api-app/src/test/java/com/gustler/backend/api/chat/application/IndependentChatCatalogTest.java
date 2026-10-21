package com.gustler.backend.api.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IndependentChatCatalogTest {
    @Test
    void 정확히_5분에_갱신하고_실패하면_30초_동안_이전_방을_유지한_뒤_회복한다() {
        // given
        MovableClock clock = new MovableClock();
        AtomicInteger reads = new AtomicInteger();
        ChatRoomCatalog catalog = new ChatRoomCatalog(() -> {
            final int attempt = reads.incrementAndGet();
            if (attempt == 2) {
                throw new IllegalStateException("unavailable");
            }
            if (attempt >= 3) {
                return List.of(new ChatRoute("123456789", "새 노선", List.of("새 정류장")));
            }
            return List.of(new ChatRoute("123456789", "기존 노선", List.of()));
        }, clock);
        assertThat(catalog.find("123456789")).hasValueSatisfying(room ->
            assertThat(room.nicknameStops()).containsExactly("기존 노선"));

        // when
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        catalog.find("123456789");
        final int readsBeforeExpiry = reads.get();
        clock.advance(Duration.ofNanos(1));
        Optional<ChatRoom> stale = catalog.find("123456789");
        final int readsAtExpiry = reads.get();
        clock.advance(Duration.ofSeconds(30).minusNanos(1));
        catalog.find("123456789");
        final int readsBeforeRetry = reads.get();
        clock.advance(Duration.ofNanos(1));
        Optional<ChatRoom> recovered = catalog.find("123456789");

        // then
        assertSoftly(softly -> {
            softly.assertThat(readsBeforeExpiry).isEqualTo(1);
            softly.assertThat(readsAtExpiry).isEqualTo(2);
            softly.assertThat(readsBeforeRetry).isEqualTo(2);
            softly.assertThat(reads).hasValue(3);
            softly.assertThat(stale).hasValueSatisfying(room ->
                assertThat(room.displayName()).isEqualTo("기존 노선"));
            softly.assertThat(recovered).hasValueSatisfying(room ->
                assertThat(room.nicknameStops()).containsExactly("새 정류장"));
        });
    }

    private static final class MovableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-20T00:00:00Z");

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
