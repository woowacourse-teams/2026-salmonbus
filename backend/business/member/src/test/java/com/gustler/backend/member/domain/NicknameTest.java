package com.gustler.backend.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.NicknameViolation;
import java.text.Normalizer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class NicknameTest {
    @ParameterizedTest
    @ValueSource(strings = {"연어", "Salmon1", "가나다라마바사아자차", "abc123"})
    void 규칙에_맞는_닉네임은_그대로_받는다(String raw) {
        // when
        Optional<Nickname> actual = Nickname.of(raw);

        // then
        assertThat(actual).map(Nickname::value).contains(raw);
    }

    @Test
    void 앞뒤_공백을_없애고_NFC로_정규화한다() {
        // given
        String decomposed = Normalizer.normalize("한글", Normalizer.Form.NFD);

        // when
        Optional<Nickname> actual = Nickname.of("  " + decomposed + " ");

        // then
        assertThat(actual).map(Nickname::value).contains("한글");
    }

    @ParameterizedTest
    @CsvSource({
        "a,LENGTH",
        "가나다라마바사아자차카,LENGTH",
        "연어 버스,CHARACTERS",
        "ㄱㄴㄷ,CHARACTERS",
        "연어😀,CHARACTERS",
        "연어_1,CHARACTERS",
        "관리자님,RESERVED",
        "ADMIN1,RESERVED",
        "연어버스팬,RESERVED",
        "SalmonBus,RESERVED",
    })
    void 규칙을_어기면_이유를_알려_준다(String raw, NicknameViolation expected) {
        // when
        Optional<NicknameViolation> actual = Nickname.violationOf(raw);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).contains(expected);
            softly.assertThat(Nickname.of(raw)).isEmpty();
        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 비어_있거나_null이면_길이_위반이다(String raw) {
        // when
        Optional<NicknameViolation> actual = Nickname.violationOf(raw);

        // then
        assertThat(actual).contains(NicknameViolation.LENGTH);
    }

    @Test
    void 규칙을_어긴_값으로는_닉네임을_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> new Nickname("a"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
