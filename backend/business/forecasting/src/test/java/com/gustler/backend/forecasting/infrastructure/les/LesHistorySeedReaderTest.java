package com.gustler.backend.forecasting.infrastructure.les;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.model.les.LesObservation;
import java.time.Instant;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LesHistorySeedReaderTest {
    @TempDir
    Path directory;

    @Test
    void 검증한_체크포인트는_순서와_정원과_미관측_좌석을_보존한다() throws Exception {
        write(
            """
                {"schema":"les-complete-history-v1","route_version_id":7,"complete_from_source_origin":true,
                 "through_batch_id":3,"through_batch_rank":2,"through_received_at":"2026-10-15T00:00:00Z",
                 "observations":[{"id":1,"batch_id":1,"batch_rank":0,"received_at":"2026-10-14T00:00:00Z",
                 "vehicle_id":"bus","passed_stop_order":4,"stop_order":5,"running_state":1,"remaining_seats":68,"crowd_level":0},
                 {"id":2,"batch_id":3,"batch_rank":2,"received_at":"2026-10-15T00:00:00Z",
                 "vehicle_id":"bus","passed_stop_order":5,"stop_order":6,"running_state":1,"remaining_seats":null,"crowd_level":0}]}
                """);
        LesHistorySeedReader.Seed seed = new LesHistorySeedReader(directory.toString(), 10).read(7);
        assertSoftly(softly -> {
            softly.assertThat(seed.rank()).isEqualTo(2);
            softly.assertThat(seed.observations().getFirst().seats()).isEqualTo(68);
            softly.assertThat(seed.observations().getLast().seats()).isNull();
        });
        LesHistorySeedReader reader = new LesHistorySeedReader(directory.toString(), 10);
        reader.write(7, seed);
        LesHistorySeedReader.Seed restored = reader.read(7);
        assertSoftly(softly -> {
            softly.assertThat(restored.observations()).hasSize(1);
            softly.assertThat(restored.prefix().get("bus").capacity()).isEqualTo(68);
            softly.assertThat(restored.rank()).isEqualTo(seed.rank());
            softly.assertThat(restored.through()).isEqualTo(seed.through());
        });
    }

    @Test
    void 빠진_좌석_필드를_영석으로_바꾸지_않는다() throws Exception {
        write("""
            {"schema":"les-complete-history-v1","route_version_id":7,"complete_from_source_origin":true,
             "through_batch_id":3,"through_batch_rank":2,"through_received_at":"2026-10-15T00:00:00Z",
             "observations":[{"id":1,"batch_id":1,"batch_rank":0,"received_at":"2026-10-14T00:00:00Z",
             "vehicle_id":"bus","passed_stop_order":4,"stop_order":5,"running_state":1,"crowd_level":0}]}
            """);
        assertThatThrownBy(() -> new LesHistorySeedReader(directory.toString(), 10).read(7))
            .hasMessageContaining("MISSING_FIELD:remaining_seats");
    }

    @Test
    void 변경된_체크포인트를_거부한다() throws Exception {
        write("{}");
        Files.writeString(directory.resolve("7.json"), "{\"modified\":true}");
        assertThatThrownBy(() -> new LesHistorySeedReader(directory.toString(), 10).read(7))
            .hasMessageContaining("HASH_MISMATCH");
    }

    @Test
    void 빈_초기_체크포인트_다음에_정상_관측을_저장할_수_있다() {
        // given
        LesHistorySeedReader reader = new LesHistorySeedReader(directory.toString(), 10);
        Instant start = Instant.parse("2026-11-04T00:00:00Z");
        reader.write(7, new LesHistorySeedReader.Seed(List.of(), 1, 0, start));

        // when
        LesHistorySeedReader.Seed empty = reader.read(7);
        LesObservation observation = new LesObservation(2, 2, 1, start.plusSeconds(20), "bus", 1, 1, 2, 30, 0);
        reader.write(7, new LesHistorySeedReader.Seed(List.of(observation), 2, 1, start.plusSeconds(20)));
        LesHistorySeedReader.Seed restored = reader.read(7);

        // then
        assertSoftly(softly -> {
            softly.assertThat(empty.observations()).isEmpty();
            softly.assertThat(empty.rank()).isZero();
            softly.assertThat(restored.observations()).containsExactly(observation);
            softly.assertThat(restored.batchId()).isEqualTo(2);
        });
    }

    private void write(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("7.json"), bytes);
        Files.writeString(directory.resolve("7.json.sha256"),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
