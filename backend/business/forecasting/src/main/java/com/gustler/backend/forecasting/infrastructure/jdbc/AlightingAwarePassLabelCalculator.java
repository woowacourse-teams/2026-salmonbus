package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.PassLabelCalculator;
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
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** 통과 정보와 직전 통과 정보로 하차 후 잔여석을 계산한다. */
public final class AlightingAwarePassLabelCalculator implements PassLabelCalculator {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Override
    public PassLabelCalculation calculate(PassClosed pass, PassClosed previous, JsonNode evidence) {
        Instant departure = pass.lastConfirmedAt();
        ZonedDateTime local = departure.atZone(SEOUL);
        AlightingTimeCell cell = AlightingTimeCell.of(local, isHoliday(evidence, departure));
        final boolean extended = !cell.isRush();
        if (extended && !evidence.has("extendedSettlement")) {
            return unsupported("OUTSIDE_FITTED_BAND");
        }

        if (previous == null) {
            return unsupported("NO_PREVIOUS_PASS");
        }

        final double headwayMinutes = Duration.between(previous.lastConfirmedAt(), departure).toNanos() / 60e9;
        if (headwayMinutes < .2 || headwayMinutes > 60) {
            return unsupported("UNSUPPORTED_HEADWAY");
        }

        JsonNode parameters = settlementParameters(evidence, cell, pass.stopOrder());
        final int bin = cell.settlementBin(local);
        if (parameters == null) {
            return unsupported("NO_FITTED_STOP_PARAMETERS");
        }

        final int capacity = evidence.path("capacity").asInt();
        if (capacity < 30 || capacity > 70) {
            return unsupported("CAPACITY_EVIDENCE_MISMATCH");
        }
        if (pass.arrivalSeats() > capacity || pass.lastSeats() > capacity) {
            return unsupported("OBSERVATION_EXCEEDS_CAPACITY_SNAPSHOT");
        }

        if (parameters.path("lam").size() != 8) {
            return unsupported("INVALID_PARAMETER_DIMENSION");
        }
        double[] lambda = IntStream.range(0, 8)
            .mapToDouble(index -> parameters.path("lam").get(index).asDouble())
            .toArray();

        AlightingPosterior.Parameters fitted = new AlightingPosterior.Parameters(
                lambda,
                parameters.path("p").asDouble(),
                parameters.path("kappa").asDouble(),
                parameters.path("r").asDouble()
        );
        final double label = AlightingPosterior.afterSeats(
                fitted,
                pass.arrivalSeats(),
                pass.lastSeats(),
                capacity,
                headwayMinutes,
                previous.lastSeats() == 0,
                bin
        );
        return new PassLabelCalculator.PassLabelCalculation(
                true,
                label,
                null,
                digest(parameters.toString())
        );
    }

    private static JsonNode settlementParameters(JsonNode evidence, AlightingTimeCell cell, final int stopOrder) {
        if (!cell.isRush()) {
            return evidence.path("extendedSettlement").get(cell.key());
        }
        return findParameters(evidence, stopOrder, cell.rushBand());
    }

    private static JsonNode findParameters(JsonNode evidence, final int stop, String band) {
        return evidence.path("alighting").path("stops").valueStream()
            .filter(parameter -> parameter.path("stop").asInt() == stop)
            .filter(parameter -> parameter.path("band").asText().equals(band))
            .findFirst()
            .orElse(null);
    }

    private static boolean isHoliday(JsonNode evidence, Instant at) {
        String day = at.atZone(SEOUL).toLocalDate().toString();
        return evidence.path("holidays").valueStream().anyMatch(holiday -> holiday.asText().equals(day));
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
