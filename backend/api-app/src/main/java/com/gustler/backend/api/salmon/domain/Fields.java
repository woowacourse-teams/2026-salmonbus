package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.Timing.*;
import java.util.Objects;
import java.util.Optional;

/** 판정 입력 값 하나가 관측, 파생, 결측 중 무엇인지와 오차 상계를 나타낸다. */
public final class Fields {
    private Fields() {
    }

    public enum MissingReason { QUERY_FAILED, NOT_PROVIDED, STALE, UNIDENTIFIED_VEHICLE, UNVERIFIED_SEQUENCE,
        COVER_BROKEN, NO_ERROR_BOUND, PREDICTION_CONFLICT, INFORMATION_TIME_MISMATCH, BASELINE_UNSTABLE,
        BUDGET_EXHAUSTED }

    public sealed interface ErrorBound permits Finite, NoBound {
    }

    public record Finite(Span bound) implements ErrorBound {
    }

    public record NoBound() implements ErrorBound {
    }

    public sealed interface Field<T> permits Observed, Derived, Missing {
        default Optional<T> present() {
            return switch (this) {
                case Observed<T> observed -> Optional.of(observed.value());
                case Derived<T> derived -> Optional.of(derived.value());
                case Missing<T> ignored -> Optional.empty();
            };
        }

        default Optional<MissingReason> missingReason() {
            return this instanceof Missing<T> missing ? Optional.of(missing.reason()) : Optional.empty();
        }

        default boolean isDerived() {
            return this instanceof Derived<T>;
        }

        default Optional<Span> knownBound() {
            return switch (this) {
                case Observed<T> observed -> Optional.of(observed.quantization().orElse(new Span(0)));
                case Derived<T> derived -> derived.bound() instanceof Finite finite
                    ? Optional.of(finite.bound()) : Optional.empty();
                case Missing<T> ignored -> Optional.empty();
            };
        }
    }

    public record Observed<T>(T value, Optional<Span> quantization) implements Field<T> {
        public Observed {
            Objects.requireNonNull(value);
            Objects.requireNonNull(quantization);
        }

        public Observed(T value) {
            this(value, Optional.empty());
        }
    }

    public record Derived<T>(T value, ErrorBound bound) implements Field<T> {
        public Derived {
            Objects.requireNonNull(value);
            Objects.requireNonNull(bound);
        }
    }

    public record Missing<T>(MissingReason reason) implements Field<T> {
        public Missing {
            Objects.requireNonNull(reason);
        }
    }
}
