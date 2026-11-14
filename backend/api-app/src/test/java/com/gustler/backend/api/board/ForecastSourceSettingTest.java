package com.gustler.backend.api.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.board.infrastructure.ForecastSourceBoardQueryRepository;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcBoardSnapshotReader;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcForecastRecordReader;
import com.gustler.backend.api.board.infrastructure.jpa.JpaBoardQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecastSourceSettingTest {

    @Test
    void 설정_이름을_대소문자와_앞뒤_공백_없이_읽는다() {
        assertThat(ForecastSourceSetting.of("legacy")).isEqualTo(ForecastSourceSetting.LEGACY);
        assertThat(ForecastSourceSetting.of(" RECORD ")).isEqualTo(ForecastSourceSetting.RECORD);
        assertThat(ForecastSourceSetting.of("Auto")).isEqualTo(ForecastSourceSetting.AUTO);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "records", "v2", "legacy,record" })
    void 세_이름이_아니면_받지_않는다(String value) {
        // when
        Throwable actual = catchThrowable(() -> ForecastSourceSetting.of(value));

        // then
        assertThat(actual)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("board.forecast-source");
    }

    @Test
    void 잘못된_설정이면_탑승판_저장소를_만들지_못한다() {
        // when
        Throwable actual = catchThrowable(() -> new ForecastSourceBoardQueryRepository(
            mock(JpaBoardQueryRepository.class), mock(JdbcBoardSnapshotReader.class),
            mock(JdbcForecastRecordReader.class), "newest"));

        // then
        assertThat(actual).isInstanceOf(IllegalArgumentException.class);
    }
}
