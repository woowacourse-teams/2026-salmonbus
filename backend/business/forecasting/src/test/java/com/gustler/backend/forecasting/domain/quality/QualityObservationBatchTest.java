package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch.Row;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QualityObservationBatchTest {
    @Test
    void 같은_차량의_정상_관측을_건너뛰고_첫_이상_관측만_조사_근거로_선택한다() {
        // given
        Row first = new Row(12, "bus-a", 71);
        Row other = new Row(14, "bus-b", 72);
        QualityObservationBatch batch =
            new QualityObservationBatch(1, 10, Instant.parse("2026-09-23T00:00:00Z"), List.of(
                new Row(11, "bus-a", 70), first,
                new Row(13, "bus-a", 99), other,
                new Row(15, " ", 99), new Row(16, null, 99)));

        // when
        List<Row> anomalies = batch.firstAnomalies();

        // then
        assertThat(anomalies).containsExactly(first, other);
    }
}
