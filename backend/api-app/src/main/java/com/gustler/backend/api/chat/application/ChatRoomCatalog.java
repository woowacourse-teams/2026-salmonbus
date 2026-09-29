package com.gustler.backend.api.chat.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ChatRoomCatalog {

    private static final Map<String, ChatRoom> ROOMS = Map.of(
        "204000057", new ChatRoom("204000057", "3330", List.of(
            "분재단지", "매화마을", "연꽃마을", "야탑역", "성남아트센터", "이매역", "서현역", "판교역",
            "도촌동", "농수산물시장", "안양시청", "범계역", "명학역", "문화의거리", "댕리단길", "안양역"
        )),
        "234000050", new ChatRoom("234000050", "1650", List.of(
            "석촌역", "돌섬", "토평정수장", "강변역", "잠실역", "석촌호수", "송파역", "가락시장",
            "가든파이브", "가천대", "목련마을", "범계사거리", "비산사거리", "댕리단길", "남부시장", "안양역"
        ))
    );

    public Optional<ChatRoom> find(String routeId) {
        return Optional.ofNullable(ROOMS.get(routeId));
    }
}
