package com.gustler.backend.api.station.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.station.InvalidStationSearchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StationSearchQueryTest {

    @Test
    void 공백과_기호를_빼고_영문은_소문자로_바꾼다() {
        // when
        StationSearchQuery query = StationSearchQuery.parse(" 솔샘중고등학교.팔팔·미송경로당 (KT&G)_2-1 ");

        // then
        assertThat(query.key()).isEqualTo("솔샘중고등학교팔팔미송경로당ktg21");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "%", "_", "...", "㈜ ·", "🚀"})
    void 비었거나_기호만_있으면_거절한다(String raw) {
        assertThatThrownBy(() -> StationSearchQuery.parse(raw)).isInstanceOf(InvalidStationSearchException.class);
    }

    @Test
    void 정규화한_글자가_40자까지만_받는다() {
        // when
        StationSearchQuery fits = StationSearchQuery.parse("가".repeat(40));

        // then
        assertThat(fits.key()).hasSize(40);
        assertThatThrownBy(() -> StationSearchQuery.parse("가".repeat(41)))
            .isInstanceOf(InvalidStationSearchException.class);
    }

    @Test
    void 길이는_기호를_뺀_뒤에_센다() {
        // when
        StationSearchQuery query = StationSearchQuery.parse("가 ".repeat(40));

        // then
        assertThat(query.key()).hasSize(40);
    }
}
