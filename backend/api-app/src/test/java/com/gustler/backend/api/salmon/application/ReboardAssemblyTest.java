package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.domain.ActionFixture.entry;
import static com.gustler.backend.api.salmon.domain.ActionFixture.queried;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.salmon.domain.Candidates.ReboardCandidate;
import com.gustler.backend.api.salmon.domain.Fields.Observed;
import com.gustler.backend.api.salmon.domain.StopLists.Queried;
import com.gustler.backend.api.salmon.domain.Timing.Span;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReboardAssemblyTest {
    @Test
    void 고른_구간의_시간과_두_도착_목록을_재탑승_후보에_담고_우회량은_받은_값만_넣는다() {
        Queried opposite = queried(entry("opp", 3));
        Queried reboard = queried(entry("back", 12));
        AlightingLeg leg = new AlightingLeg("100", 4, 180, "200", 12, 420, "300", 9, 90);

        ReboardCandidate candidate = ReboardAssembly.candidate(leg, opposite, reboard, Optional.empty());

        assertThat(candidate.id()).isEqualTo("reboard-100-4-180-200-12-420-300-9-90");
        assertThat(candidate.walkToOpposite()).isEqualTo(new Observed<>(new Span(180_000)));
        assertThat(candidate.rideTime()).isEqualTo(new Observed<>(new Span(420_000)));
        assertThat(candidate.walkAfterAlight()).isEqualTo(new Observed<>(new Span(90_000)));
        assertThat(candidate.opposite()).isSameAs(opposite);
        assertThat(candidate.reboard()).isSameAs(reboard);
        assertThat(candidate.detour()).isEmpty();
    }

    @Test
    void 정류장이_비었거나_시간이_음수면_구간을_만들지_않는다() {
        assertThatThrownBy(() -> new AlightingLeg(" ", 4, 0, "200", 12, 0, "300", 9, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlightingLeg("100", 0, 0, "200", 12, 0, "300", 9, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlightingLeg("100", 4, -1, "200", 12, 0, "300", 9, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
