package com.gustler.backend.api.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatNicknameStopsTest {

    @Test
    void 정류장_이름에서_괄호와_마침표_뒤와_끝의_앞을_뗀다() {
        assertThat(ChatNicknameStops.clean("서울역버스환승센터(5번승강장)(중)")).isEqualTo("서울역버스환승센터");
        assertThat(ChatNicknameStops.clean("도촌초등학교.도촌종합사회복지관")).isEqualTo("도촌초등학교");
        assertThat(ChatNicknameStops.clean("도촌동9단지앞")).isEqualTo("도촌동9단지");
        assertThat(ChatNicknameStops.clean("앞")).isEqualTo("앞");
        assertThat(ChatNicknameStops.clean("(중)")).isEmpty();
    }

    @Test
    void 짧은_이름이_충분하면_짧은_이름만_중복_없이_순서대로_쓴다() {
        // given
        List<String> stopNames = List.of(
            "야탑역", "야탑역", "서현역앞", "이매역", "판교역", "범계역", "명학역", "안양역", "금정역",
            "서울역버스환승센터(5번승강장)"
        );

        // when
        List<String> stops = ChatNicknameStops.from(stopNames);

        // then
        assertThat(stops).containsExactly("야탑역", "서현역", "이매역", "판교역", "범계역", "명학역", "안양역", "금정역");
    }

    @Test
    void 짧은_이름이_모자라면_긴_이름도_쓴다() {
        // given
        List<String> stopNames = List.of("야탑역", "서울역버스환승센터", "성남종합운동장입구");

        // when
        List<String> stops = ChatNicknameStops.from(stopNames);

        // then
        assertThat(stops).containsExactly("야탑역", "서울역버스환승센터", "성남종합운동장입구");
    }
}
