package com.gustler.backend.forecasting.infrastructure.jdbc;

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

/** 확인 시각으로 닫힌 방문과 같은 날 직전 통과를 예보 당시의 차량 정원·계수와 연결해 새 정답 판본의 하차 후 잔여석을 계산한다. */
final class ConfirmedAlightingAwareEvaluation {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private ConfirmedAlightingAwareEvaluation() {
    }

    static JdbcAlightingAwareForecastEvaluation.Outcome afterSeats(
        AlightingAwarePassEvent visit, AlightingAwarePassEvent previous, JsonNode snapshot
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
        JsonNode parameters = extended ? snapshot.path("extendedSettlement").get(cell.key())
            : findParameters(snapshot, visit.stopOrder(), cell.rushBand());
        final int bin = cell.settlementBin(local);
        if (parameters == null) {
            return unsupported("NO_FITTED_STOP_PARAMETERS");
        }
        int capacity = snapshot.path("capacity").asInt();
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
        AlightingPosterior.Parameters fitted = new AlightingPosterior.Parameters(lambda,
            parameters.path("p").asDouble(), parameters.path("kappa").asDouble(), parameters.path("r").asDouble());
        final double label = AlightingPosterior.afterSeats(fitted, visit.arrival().seats(),
            visit.departure().seats(),
            capacity, gap, previous.departure().seats() == 0, bin);
        return new JdbcAlightingAwareForecastEvaluation.Outcome("SETTLED", label, null, visit, previous, null,
            digest(parameters.toString()), capacity);
    }

    private static JsonNode findParameters(JsonNode snapshot, final int stop, String band) {
        for (JsonNode parameter : snapshot.path("alighting").path("stops")) {
            if (parameter.path("stop").asInt() == stop && parameter.path("band").asText().equals(band)) {
                return parameter;
            }
        }
        return null;
    }

    private static boolean isHoliday(JsonNode snapshot, Instant at) {
        String day = at.atZone(SEOUL).toLocalDate().toString();
        for (JsonNode holiday : snapshot.path("holidays")) {
            if (holiday.asText().equals(day)) {
                return true;
            }
        }
        return false;
    }

    private static JdbcAlightingAwareForecastEvaluation.Outcome unsupported(String reason) {
        return JdbcAlightingAwareForecastEvaluation.Outcome.unsupported(reason);
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
