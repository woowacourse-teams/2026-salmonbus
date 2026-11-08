package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.domain.ActionModel.Derived;
import com.gustler.backend.api.salmon.domain.ActionModel.Field;
import com.gustler.backend.api.salmon.domain.ActionModel.Finite;
import com.gustler.backend.api.salmon.domain.ActionModel.Missing;
import com.gustler.backend.api.salmon.domain.ActionModel.MissingReason;
import com.gustler.backend.api.salmon.domain.ActionModel.NoBound;
import com.gustler.backend.api.salmon.domain.ActionModel.Span;
import com.gustler.backend.api.salmon.domain.EtaChain;
import com.gustler.backend.api.salmon.domain.EtaChain.Known;
import com.gustler.backend.api.salmon.domain.EtaChain.Pattern;
import com.gustler.backend.api.salmon.domain.EtaChain.Reason;
import com.gustler.backend.api.salmon.domain.EtaChain.Run;
import com.gustler.backend.api.salmon.domain.EtaChain.Segment;
import com.gustler.backend.api.salmon.domain.EtaChain.Visit;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EtaFieldsTest {
    private static final Pattern UP = new Pattern("route", "version", "UP", "pattern");
    private static final Run RUN = new Run(UP, "vehicle", "trip");

    @Test
    void 상계가_있는_구간은_유한_상계의_파생_필드가_된다() {
        Field<Span> field = EtaFields.rideTime(new Known<>(segment(Optional.of(Duration.ofMinutes(2)))));

        assertThat(field).isEqualTo(new Derived<>(new Span(300_000), new Finite(new Span(120_000))));
    }

    @Test
    void 상계가_없는_구간은_상계_없음의_파생_필드가_된다() {
        Field<Span> field = EtaFields.rideTime(new Known<>(segment(Optional.empty())));

        assertThat(field).isEqualTo(new Derived<>(new Span(300_000), new NoBound()));
    }

    @Test
    void 이어붙이지_못한_구간은_사유를_옮긴_결측이다() {
        assertThat(EtaFields.rideTime(new EtaChain.Missing<>(Reason.COVER_BROKEN)))
            .isEqualTo(new Missing<>(MissingReason.COVER_BROKEN));
        assertThat(EtaFields.rideTime(new EtaChain.Missing<>(Reason.NO_ERROR_BOUND)))
            .isEqualTo(new Missing<>(MissingReason.NO_ERROR_BOUND));
        assertThat(EtaFields.rideTime(new EtaChain.Missing<>(Reason.RUN_MISMATCH)))
            .isEqualTo(new Missing<>(MissingReason.UNIDENTIFIED_VEHICLE));
        assertThat(EtaFields.rideTime(new EtaChain.Missing<>(Reason.SEQUENCE_MISMATCH)))
            .isEqualTo(new Missing<>(MissingReason.UNVERIFIED_SEQUENCE));
        assertThat(EtaFields.rideTime(new EtaChain.Missing<>(Reason.INFORMATION_MISMATCH)))
            .isEqualTo(new Missing<>(MissingReason.INFORMATION_TIME_MISMATCH));
    }

    private static Segment segment(Optional<Duration> bound) {
        return new Segment(UP, Optional.of(RUN), new Visit("opp", 1), new Visit("off", 2), Duration.ofMinutes(5),
            bound, List.of("observation"), EtaChain.Method.SAME_RUN);
    }
}
