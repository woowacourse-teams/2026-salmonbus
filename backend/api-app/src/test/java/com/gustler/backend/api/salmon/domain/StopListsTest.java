package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.ActionFixture.offsetOf;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.salmon.domain.Fields.Observed;
import com.gustler.backend.api.salmon.domain.StopLists.StopEntry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StopListsTest {
    @Test
    void 차량_ID가_빈_문자열이면_항목을_만들지_않는다() {
        assertThatThrownBy(() -> new StopEntry("s0-3", new Observed<>(offsetOf(3)), Optional.of(" ")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
