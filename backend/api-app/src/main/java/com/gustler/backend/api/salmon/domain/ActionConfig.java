package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionModel.CandidateKind;
import com.gustler.backend.api.salmon.domain.ActionModel.Offset;
import com.gustler.backend.api.salmon.domain.ActionModel.Span;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record ActionConfig(double scanThreshold, double gainThreshold, BigDecimal alpha, Offset horizon,
                           Span maxWalk, double maxDetour, Span resolution, int epochBudget,
                           List<CandidateKind> kindOrder) {
    public ActionConfig {
        if (!(scanThreshold >= 0 && scanThreshold <= 1)) {
            throw new IllegalArgumentException("scanThreshold must be within [0, 1]");
        }
        if (!(gainThreshold > 0 && gainThreshold <= 1)) {
            throw new IllegalArgumentException("gainThreshold must be within (0, 1]");
        }
        if (alpha.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("alpha must be at least 1");
        }
        if (horizon.millis() <= 0) {
            throw new IllegalArgumentException("horizon must be positive");
        }
        if (resolution.millis() <= 0) {
            throw new IllegalArgumentException("resolution must be positive");
        }
        if (epochBudget < 1) {
            throw new IllegalArgumentException("epochBudget must be at least 1");
        }
        if (Double.isNaN(maxDetour) || maxDetour < 0) {
            throw new IllegalArgumentException("maxDetour must not be negative");
        }
        if (kindOrder.size() != CandidateKind.values().length || kindOrder.stream().distinct().count() != kindOrder.size()) {
            throw new IllegalArgumentException("kindOrder must list every candidate kind once");
        }
        kindOrder = List.copyOf(kindOrder);
    }

    Span readyAfterWalk(Span walk) {
        return ceilToResolution(scaledByAlpha(walk));
    }

    Span scaledByAlpha(Span walk) {
        long scaled = alpha.multiply(BigDecimal.valueOf(walk.millis())).setScale(0, RoundingMode.CEILING).longValueExact();
        return new Span(scaled);
    }

    Span ceilToResolution(Span span) {
        long step = resolution.millis();
        return new Span(Math.floorDiv(span.millis() + step - 1, step) * step);
    }

    Offset ceilToResolution(Offset offset) {
        long step = resolution.millis();
        return new Offset(Math.floorDiv(offset.millis() + step - 1, step) * step);
    }
}
