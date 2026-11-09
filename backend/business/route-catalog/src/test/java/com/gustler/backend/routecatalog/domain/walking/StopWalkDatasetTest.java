package com.gustler.backend.routecatalog.domain.walking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.routecatalog.domain.StopCoordinates;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StopWalkDatasetTest {
    private static final OffsetDateTime OSM_BASE = OffsetDateTime.parse("2026-11-02T21:21:26Z");
    private static final StopCoordinates HERE = new StopCoordinates(127.16, 37.41);

    @Test
    void 같은_정류장_쌍이_두_번_들어_있으면_묶음이_아니다() {
        StopWalkDistance pair = new StopWalkDistance("205000217", "205000220", HERE, HERE, 161);

        assertThatThrownBy(() -> new StopWalkDataset("a".repeat(64), OSM_BASE, List.of(pair, pair)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 체크섬은_SHA_256_16진수여야_한다() {
        assertThatThrownBy(() -> new StopWalkDataset("not-a-digest", OSM_BASE, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
