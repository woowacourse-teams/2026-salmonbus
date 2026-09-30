package com.gustler.backend.api.chat.application;

import java.time.Clock;
import java.util.List;

public final class ChatTestRoutes {

    public static final ChatRoute ROUTE_3330 = new ChatRoute("204000057", "3330", List.of(
        "분재단지", "매화마을", "연꽃마을", "야탑역", "성남아트센터", "이매역", "서현역", "판교역",
        "도촌동", "농수산물시장", "안양시청", "범계역", "명학역", "문화의거리", "댕리단길", "안양역"
    ));
    public static final ChatRoute ROUTE_1650 = new ChatRoute("234000050", "1650", List.of(
        "석촌역", "돌섬", "토평정수장", "강변역", "잠실역", "석촌호수", "송파역", "가락시장",
        "가든파이브", "가천대", "목련마을", "범계사거리", "비산사거리", "댕리단길", "남부시장", "안양역"
    ));

    private ChatTestRoutes() {
    }

    public static ChatRouteSource source() {
        return () -> List.of(ROUTE_3330, ROUTE_1650);
    }

    public static ChatRoomCatalog catalog() {
        return new ChatRoomCatalog(source(), Clock.systemUTC());
    }
}
