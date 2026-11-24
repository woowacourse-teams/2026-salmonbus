package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.forecast.PassLabelCalculator;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingPosterior;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;

/** 확인 시각으로 닫힌 방문과 같은 날 직전 통과를 예보 당시의 차량 정원, 계수와 연결해 하차 후 잔여석(정답)을 계산한다. */
final class AlightingAwarePassLabel {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private AlightingAwarePassLabel() {
    }

    static PassLabelCalculator.PassLabelCalculation afterSeats(
        AlightingAwarePassEvent visit,
        AlightingAwarePassEvent previous,
        JsonNode snapshot
    ) {
        Instant departure = visit.departure().receivedAt();
        ZonedDateTime local = departure.atZone(SEOUL);
        AlightingTimeCell cell = AlightingTimeCell.of(local, isHoliday(snapshot, departure));
        final boolean extended = !cell.isRush();
        if (extended && !snapshot.has("extendedSettlement")) {
            return unsupported("OUTSIDE_FITTED_BAND");
        }
        if (previous == null) {
            return unsupported("NO_PREVIOUS_PASS");
        }
        final double gap = Duration.between(previous.departure().receivedAt(), departure).toNanos() / 60e9;
        if (gap < .2 || gap > 60) {
            return unsupported("UNSUPPORTED_HEADWAY");
        }
        JsonNode parameters = settlementParameters(snapshot, cell, visit.stopOrder());
        final int bin = cell.settlementBin(local);
        if (parameters == null) {
            return unsupported("NO_FITTED_STOP_PARAMETERS");
        }
        final int capacity = snapshot.path("capacity").asInt();
        if (capacity < 30 || capacity > 70) {
            return unsupported("CAPACITY_EVIDENCE_MISMATCH");
        }
        if (visit.arrival().seats() > capacity || visit.departure().seats() > capacity) {
            return unsupported("OBSERVATION_EXCEEDS_CAPACITY_SNAPSHOT");
        }
        double[] lambda = new double[8];
        if (parameters.path("lam").size() != lambda.length) {
            return unsupported("INVALID_PARAMETER_DIMENSION");
        }
        for (int i = 0; i < lambda.length; i++) {
            lambda[i] = parameters.path("lam").get(i).asDouble();
        }
        AlightingPosterior.Parameters fitted = new AlightingPosterior.Parameters(
                lambda,
                parameters.path("p").asDouble(),
                parameters.path("kappa").asDouble(),
                parameters.path("r").asDouble()
        );
        final double label = AlightingPosterior.afterSeats(
                fitted,
                visit.arrival().seats(),
                visit.departure().seats(),
                capacity,
                gap,
                previous.departure().seats() == 0,
                bin
        );
        return new PassLabelCalculator.PassLabelCalculation(
                true,
                label,
                null,
                digest(parameters.toString())
        );
    }

    private static JsonNode settlementParameters(JsonNode snapshot, AlightingTimeCell cell, final int stopOrder) {
        if (!cell.isRush()) {
            return snapshot.path("extendedSettlement").get(cell.key());
        }
        return findParameters(snapshot, stopOrder, cell.rushBand());
    }

    private static JsonNode findParameters(JsonNode snapshot, final int stop, String band) {
        return snapshot.path("alighting").path("stops").valueStream()
            .filter(parameter -> parameter.path("stop").asInt() == stop)
            .filter(parameter -> parameter.path("band").asText().equals(band))
            .findFirst()
            .orElse(null);
    }

    private static boolean isHoliday(JsonNode snapshot, Instant at) {
        String day = at.atZone(SEOUL).toLocalDate().toString();
        return snapshot.path("holidays").valueStream().anyMatch(holiday -> holiday.asText().equals(day));
    }

    private static PassLabelCalculator.PassLabelCalculation unsupported(String reason) {
        return PassLabelCalculator.PassLabelCalculation.unsupported(reason);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
