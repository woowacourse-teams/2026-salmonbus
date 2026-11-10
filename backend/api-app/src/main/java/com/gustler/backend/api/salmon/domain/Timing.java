package com.gustler.backend.api.salmon.domain;

/** 시각 0부터의 오프셋과 지속시간을 밀리초로 다룬다. */
public final class Timing {
    private Timing() {
    }

    public record Offset(long millis) implements Comparable<Offset> {
        public static Offset ofMinutes(double minutes) {
            return new Offset(Math.round(minutes * 60_000));
        }

        public Offset plus(Span span) {
            return new Offset(millis + span.millis());
        }

        public Span until(Offset later) {
            return new Span(later.millis - millis);
        }

        public double minutes() {
            return millis / 60_000.0;
        }

        @Override
        public int compareTo(Offset other) {
            return Long.compare(millis, other.millis);
        }
    }

    public record Span(long millis) implements Comparable<Span> {
        public Span {
            if (millis < 0) {
                throw new IllegalArgumentException("Span must not be negative");
            }
        }

        public static Span ofMinutes(double minutes) {
            return new Span(Math.round(minutes * 60_000));
        }

        public Span plus(Span other) {
            return new Span(millis + other.millis);
        }

        public double minutes() {
            return millis / 60_000.0;
        }

        @Override
        public int compareTo(Span other) {
            return Long.compare(millis, other.millis);
        }
    }
}
