package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.ActionModel.Derived;
import com.gustler.backend.api.salmon.domain.ActionModel.ErrorBound;
import com.gustler.backend.api.salmon.domain.ActionModel.Field;
import com.gustler.backend.api.salmon.domain.ActionModel.Finite;
import com.gustler.backend.api.salmon.domain.ActionModel.Missing;
import com.gustler.backend.api.salmon.domain.ActionModel.MissingReason;
import com.gustler.backend.api.salmon.domain.ActionModel.NoBound;
import com.gustler.backend.api.salmon.domain.ActionModel.Span;
import com.gustler.backend.api.salmon.domain.EtaChain;
import com.gustler.backend.api.salmon.domain.EtaChain.Known;
import com.gustler.backend.api.salmon.domain.EtaChain.Result;
import com.gustler.backend.api.salmon.domain.EtaChain.Segment;
import java.time.Duration;
import java.util.Optional;

public final class EtaFields {
    private EtaFields() {
    }

    public static Field<Span> rideTime(Result<Segment> segment) {
        return switch (segment) {
            case Known<Segment> known -> new Derived<>(spanOf(known.value().travelTime()),
                boundOf(known.value().errorBound()));
            case EtaChain.Missing<Segment> missing -> new Missing<>(reasonOf(missing.reason()));
        };
    }

    private static Span spanOf(Duration duration) {
        return new Span(duration.toMillis());
    }

    private static ErrorBound boundOf(Optional<Duration> bound) {
        return bound.<ErrorBound>map(value -> new Finite(spanOf(value))).orElseGet(NoBound::new);
    }

    private static MissingReason reasonOf(EtaChain.Reason reason) {
        return switch (reason) {
            case RUN_MISMATCH, PATTERN_MISMATCH -> MissingReason.UNIDENTIFIED_VEHICLE;
            case SEQUENCE_MISMATCH -> MissingReason.UNVERIFIED_SEQUENCE;
            case INFORMATION_MISMATCH -> MissingReason.INFORMATION_TIME_MISMATCH;
            case PREDICTION_CONFLICT -> MissingReason.PREDICTION_CONFLICT;
            case COVER_BROKEN -> MissingReason.COVER_BROKEN;
            case NO_ERROR_BOUND -> MissingReason.NO_ERROR_BOUND;
        };
    }
}
