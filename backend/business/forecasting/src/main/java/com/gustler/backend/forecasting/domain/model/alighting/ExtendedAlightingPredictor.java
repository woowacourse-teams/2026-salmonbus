package com.gustler.backend.forecasting.domain.model.alighting;

import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 출퇴근 외 시간대에는 원관측과 목표 정류장의 정보로 두 예보 값을 계산한다. */
public record ExtendedAlightingPredictor(NumericTreeEnsemble seats, NumericTreeEnsemble full) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public ExtendedAlightingPredictor {
        if (seats.kind() != NumericTreeEnsemble.Kind.REGRESSION || seats.featureCount() != 6) {
            throw new IllegalArgumentException("시간 확장 좌석 모델 입력 계약이 다르다");
        }
        if (full.kind() != NumericTreeEnsemble.Kind.BINARY || full.featureCount() != 6) {
            throw new IllegalArgumentException("시간 확장 만석 모델 입력 계약이 다르다");
        }
    }

    public static double[] inputs(
        AlightingAwareFeatureSnapshot snapshot, VehicleStopTarget target, final boolean offDay
    ) {
        ZonedDateTime local = snapshot.source().receivedAt().atZone(SEOUL);
        final int minute = local.getHour() * 60 + local.getMinute();
        double offDayValue = 0;
        if (offDay) {
            offDayValue = 1;
        }
        return new double[] {target.remainingSeats(), snapshot.capacity(), target.stopOrder(),
            target.distance().stopCount(), minute, offDayValue};
    }

    public SeatForecastResult predict(double[] inputs) {
        final double expected = Math.max(0, Math.min(inputs[1], seats.predict(inputs)[0]));
        return SeatForecastResult.alighting(expected, full.predict(inputs)[1]);
    }
}
