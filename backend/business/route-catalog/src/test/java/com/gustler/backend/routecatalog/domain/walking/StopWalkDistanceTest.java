package com.gustler.backend.routecatalog.domain.walking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.routecatalog.domain.StopCoordinates;
import org.junit.jupiter.api.Test;

class StopWalkDistanceTest {
    private static final StopCoordinates HERE = new StopCoordinates(127.16, 37.41);

    @Test
    void 정류장_ID가_작은_쪽이_앞에_와야_한다() {
        assertThatThrownBy(() -> new StopWalkDistance("205000220", "205000217", HERE, HERE, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_정류장끼리는_쌍이_아니다() {
        assertThatThrownBy(() -> new StopWalkDistance("205000217", "205000217", HERE, HERE, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 도보_거리는_음수일_수_없다() {
        assertThatThrownBy(() -> new StopWalkDistance("205000217", "205000220", HERE, HERE, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
