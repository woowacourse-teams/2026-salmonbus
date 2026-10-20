package com.gustler.backend.forecasting.domain.publication;

import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.time.Clock;

/**
 * 예보에 사용할 시간대의 판정 규칙을 모은다.
 *
 * <p><b>관측을 받은 시각으로 정한다.</b> 예보 계산 시각을 쓰지 않는 이유는 두 가지다.
 *
 * <ul>
 *   <li>예보는 관측에 연결되고 응답도 해당 시각을 관측 시각으로 반환한다. 시간대만 다른 시각으로
 *       정하면 한 행에 두 기준 시각이 섞인다
 *   <li>밀린 batch를 나중에 실행해도 같은 값이 나온다. 계산 시각을 쓰면 실행 시점에 따라
 *       아침이 그 밖으로 바뀐다
 * </ul>
 *
 * <p>batch 전체를 받아 호출자가 관측 시각 대신 계산 시각을 넘기지 않도록 한다.
 *
 * <p>대상 정류장에 도착할 시각을 기준으로 정하는 안도 있지만 이번에는 사용하지 않는다.
 * 적용하려면 도착까지 걸리는 시간을 학습과 서빙에서 같은 규칙으로 함께 바꿔야 한다.
 */
public final class ForecastTimeSlot {
    private ForecastTimeSlot() {
    }

    public static TimeSlot of(
        PendingForecastBatch batch,
        Clock clock
    ) {
        return TimeSlot.of(batch.responseReceivedAt(), clock);
    }
}
