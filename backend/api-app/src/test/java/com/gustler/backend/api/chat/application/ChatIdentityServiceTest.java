package com.gustler.backend.api.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChatIdentityServiceTest {

    private static final List<String> ROUTE_3330_STOPS = List.of(
        "분재단지", "매화마을", "연꽃마을", "야탑역", "성남아트센터", "이매역", "서현역", "판교역",
        "도촌동", "농수산물시장", "안양시청", "범계역", "명학역", "문화의거리", "댕리단길", "안양역"
    );
    private static final List<String> ROUTE_1650_STOPS = List.of(
        "석촌역", "돌섬", "토평정수장", "강변역", "잠실역", "석촌호수", "송파역", "가락시장",
        "가든파이브", "가천대", "목련마을", "범계사거리", "비산사거리", "댕리단길", "남부시장", "안양역"
    );

    private final ChatIdentityService service = new ChatIdentityService();
    private final ChatRoomCatalog catalog = new ChatRoomCatalog();

    @Test
    void 같은_노선과_세션은_같은_익명_신원을_만든다() {
        UUID sessionId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        ChatRoom room = room("204000057");

        ChatIdentity first = service.derive(room, sessionId);
        ChatIdentity second = service.derive(room, sessionId);

        assertThat(second).isEqualTo(first);
        assertThat(first.authorId()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(first.nickname())
            .contains(" ")
            .doesNotMatch(".*\\d+$");
    }

    @Test
    void 노선이_달라지면_신원도_달라진다() {
        UUID sessionId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

        assertThat(service.derive(room("204000057"), sessionId))
            .isNotEqualTo(service.derive(room("234000050"), sessionId));
    }

    @Test
    void 방마다_그_노선의_정류장_목록을_이_순서로_둔다() {
        // then
        assertThat(room("204000057").nicknameStops()).containsExactlyElementsOf(ROUTE_3330_STOPS);
        assertThat(room("234000050").nicknameStops()).containsExactlyElementsOf(ROUTE_1650_STOPS);
    }

    @Test
    void 닉네임의_정류장은_그_방_목록에서만_나오고_숫자가_없다() {
        // given
        List<UUID> sessionIds = IntStream.range(0, 200)
            .mapToObj(index -> UUID.nameUUIDFromBytes(("session-" + index).getBytes()))
            .toList();

        for (String routeId : List.of("204000057", "234000050")) {
            ChatRoom room = room(routeId);

            // when
            List<String> stops = sessionIds.stream()
                .map(sessionId -> service.derive(room, sessionId).nickname())
                .map(nickname -> nickname.substring(nickname.indexOf(' ') + 1))
                .toList();

            // then
            assertThat(room.nicknameStops()).containsAll(stops);
            assertThat(stops).allSatisfy(stop -> assertThat(stop).doesNotContainPattern("\\d"));
            assertThat(stops).containsAll(room.nicknameStops());
        }
    }

    @Test
    void 두_방에서_같은_세션의_파생은_서로_다르다() {
        // given
        List<UUID> sessionIds = IntStream.range(0, 50)
            .mapToObj(index -> UUID.nameUUIDFromBytes(("session-" + index).getBytes()))
            .toList();

        for (UUID sessionId : sessionIds) {
            // when
            ChatIdentity inRoute3330 = service.derive(room("204000057"), sessionId);
            ChatIdentity inRoute1650 = service.derive(room("234000050"), sessionId);

            // then
            assertThat(inRoute3330.authorId()).isNotEqualTo(inRoute1650.authorId());
        }
    }

    private ChatRoom room(String routeId) {
        return catalog.find(routeId).orElseThrow();
    }
}
