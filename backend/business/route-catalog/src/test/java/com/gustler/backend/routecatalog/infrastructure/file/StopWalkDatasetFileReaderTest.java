package com.gustler.backend.routecatalog.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.routecatalog.domain.StopCoordinates;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDataset;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDistance;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class StopWalkDatasetFileReaderTest {
    private final StopWalkDatasetFileReader reader = new StopWalkDatasetFileReader();

    @Test
    void 정류장_쌍과_계산에_쓴_좌표와_거리를_읽는다() {
        StopWalkDataset dataset = read("two-pairs", "two-pairs");

        assertThat(dataset.checksum()).isEqualTo("562d2d24f2ca0f5c7b9712c3c355b0eb2ae8c6183276b9a3ebf9dda3cae52432");
        assertThat(dataset.osmBase()).isEqualTo(OffsetDateTime.parse("2026-11-02T21:21:26Z"));
        assertThat(dataset.pairs()).containsExactly(
            new StopWalkDistance("205000217", "205000220", new StopCoordinates(127.1616, 37.4120167),
                new StopCoordinates(127.1627833, 37.4118167), 161),
            new StopWalkDistance("205000217", "205000231", new StopCoordinates(127.1616, 37.4120167),
                new StopCoordinates(127.1487833, 37.4146333), 1_180));
    }

    @Test
    void 도보_거리가_빈_쌍이_있으면_읽지_않는다() {
        assertThatThrownBy(() -> read("unreachable", "unreachable"))
            .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void 머리줄이_다르면_읽지_않는다() {
        assertThatThrownBy(() -> read("wrong-header", "wrong-header"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("머리줄");
    }

    @Test
    void 압축_파일의_지문이_메타데이터와_다르면_읽지_않는다() {
        assertThatThrownBy(() -> read("two-pairs", "wrong-header"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("지문");
    }

    @Test
    void 쌍_수가_메타데이터와_다르면_읽지_않는다() {
        assertThatThrownBy(() -> read("two-pairs", "two-pairs-wrong-count"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("쌍 수");
    }

    private StopWalkDataset read(String distances, String metadata) {
        return reader.read(fixture(distances + ".csv.gz"), fixture(metadata + ".json"));
    }

    static Path fixture(String name) {
        try {
            return Path.of(StopWalkDatasetFileReaderTest.class.getResource("/walking-fixture/" + name).toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
