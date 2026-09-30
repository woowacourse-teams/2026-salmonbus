package com.gustler.backend.api.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatRoomCatalogTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-30T02:00:00Z"));

    @Test
    void 현재_노선마다_채팅방을_만들고_없는_노선은_비어_있다() {
        // given
        ChatRoomCatalog catalog = new ChatRoomCatalog(
            () -> List.of(new ChatRoute("204000070", "9007", List.of("운중동먹거리촌앞", "판교역", "서울역"))),
            clock
        );

        // when & then
        assertThat(catalog.find("204000070")).hasValueSatisfying(room -> {
            assertThat(room.displayName()).isEqualTo("9007");
            assertThat(room.nicknameStops()).containsExactly("운중동먹거리촌", "판교역", "서울역");
        });
        assertThat(catalog.find("999999999")).isEmpty();
    }

    @Test
    void 정류장이_없는_노선은_노선_이름으로_닉네임을_만든다() {
        // given
        ChatRoomCatalog catalog = new ChatRoomCatalog(() -> List.of(new ChatRoute("204000070", "9007", List.of())), clock);

        // when & then
        assertThat(catalog.find("204000070")).hasValueSatisfying(
            room -> assertThat(room.nicknameStops()).containsExactly("9007")
        );
    }

    @Test
    void 목록은_잠시_들고_있다가_시간이_지나면_다시_읽는다() {
        // given
        AtomicInteger reads = new AtomicInteger();
        List<ChatRoute> routes = new ArrayList<>(List.of(ChatTestRoutes.ROUTE_3330));
        ChatRoomCatalog catalog = new ChatRoomCatalog(() -> {
            reads.incrementAndGet();
            return List.copyOf(routes);
        }, clock);
        catalog.find("204000057");
        routes.add(ChatTestRoutes.ROUTE_1650);

        // when
        boolean foundBeforeRefresh = catalog.find("234000050").isPresent();
        clock.advance(Duration.ofMinutes(6));
        boolean foundAfterRefresh = catalog.find("234000050").isPresent();

        // then
        assertThat(foundBeforeRefresh).isFalse();
        assertThat(foundAfterRefresh).isTrue();
        assertThat(reads).hasValue(2);
    }

    @Test
    void 다시_읽다가_실패하면_이전_목록을_쓴다() {
        // given
        AtomicInteger reads = new AtomicInteger();
        ChatRoomCatalog catalog = new ChatRoomCatalog(() -> {
            if (reads.incrementAndGet() > 1) {
                throw new IllegalStateException("db down");
            }
            return List.of(ChatTestRoutes.ROUTE_3330);
        }, clock);
        catalog.find("204000057");
        clock.advance(Duration.ofMinutes(6));

        // when & then
        assertThat(catalog.find("204000057")).isPresent();
    }

    @Test
    void 처음_읽기부터_실패하면_실패를_그대로_던진다() {
        // given
        ChatRoomCatalog catalog = new ChatRoomCatalog(() -> {
            throw new IllegalStateException("db down");
        }, clock);

        // when & then
        assertThatThrownBy(() -> catalog.find("204000057")).isInstanceOf(IllegalStateException.class);
    }

    private static final class MovableClock extends Clock {

        private Instant instant;

        private MovableClock(Instant instant) {
            this.instant = instant;
        }

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
