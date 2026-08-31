package com.gustler.backend.processor;

import java.time.Clock;

/**
 * 예보 하나가 쓸 시간대를 정하는 한 자리.
 *
 * <p><b>관측을 받은 시각으로 정한다.</b> 예보를 계산한 시각이 아니다. 이유 둘이다.
 *
 * <ul>
 *   <li>예보가 그 관측에 매달리고 응답도 그 시각을 관측 시각으로 낸다. 시간대만 다른 시각을
 *       보면 한 행이 두 시각을 말하게 된다
 *   <li>밀린 batch 를 뒤늦게 돌려도 같은 값이 나온다. 계산 시각으로 정하면 언제 돌렸느냐로
 *       아침이 그 밖이 된다
 * </ul>
 *
 * <p>batch 를 통째로 받는다. 시각을 받으면 부르는 쪽이 계산 시각을 넣을 수 있어서다.
 *
 * <p>대상 정류장에 도착할 시각으로 정하는 안도 있는데 이번에 안 쓴다. 도착까지 걸리는 시간을
 * 학습과 서빙이 같은 규칙으로 함께 바꿔야 한다.
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
