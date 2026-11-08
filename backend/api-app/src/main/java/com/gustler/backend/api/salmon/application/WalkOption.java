package com.gustler.backend.api.salmon.application;

public record WalkOption(String stationId, int stopSequence, Relation relation, int walkSeconds) {
    public enum Relation {
        BEFORE, AFTER, OPPOSITE, SAME_STATION, UNKNOWN;

        public static Relation parse(String wire) {
            return switch (wire) {
                case "BEFORE" -> BEFORE;
                case "AFTER" -> AFTER;
                case "OPPOSITE" -> OPPOSITE;
                case "SAME_STATION" -> SAME_STATION;
                default -> UNKNOWN;
            };
        }
    }

    public WalkOption {
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("stationId must not be blank");
        }
        if (stopSequence < 1 || walkSeconds < 0) {
            throw new IllegalArgumentException("stopSequence must be positive and walkSeconds must not be negative");
        }
    }
}
