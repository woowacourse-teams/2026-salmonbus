package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.SeatForecastDesignMatrix;
import com.gustler.backend.processor.SeatForecastInput;
import com.gustler.backend.processor.SeatForecastModel;
import com.gustler.backend.processor.SeatForecastResult;

/**
 * 예보 재료를 설계행렬 31열로 펴서 좌석 분포 계산에 넘긴다.
 *
 * <p>예보 경로와 계수 계산 사이를 잇는 자리다. 재료에서 열을 만드는 것은 설계행렬이 하고,
 * 열에서 확률을 내는 것은 예측기가 한다. 여기는 그 둘을 붙이고 노선 이름을 옮기기만 한다.
 *
 * <p><b>DB 를 안 읽는다.</b> 어느 노선인지도 재료 안의 정류장 목록에서 꺼낸다. 그래서 과거 시점
 * 재료를 손으로 만들어 넣으면 그때의 예보가 그대로 다시 나온다.
 *
 * <p>당일 성적으로 만석 확률을 옮기는 자리는 아직 안 넘긴다. 그 값을 DB 에서 읽는 질의가 없어서,
 * 지금은 보정 없이 양 끝만 자른 확률이 나간다. 넘기기 시작하면 이 줄만 바뀐다.
 */
public final class SeatDistributionForecastModel implements SeatForecastModel {

    private final SeatDistributionPredictor predictor;

    public SeatDistributionForecastModel(
        SeatDistributionPredictor predictor
    ) {
        this.predictor = predictor;
    }

    @Override
    public SeatForecastResult predict(
        SeatForecastInput input
    ) {
        return predictor.predict(new SeatDistributionInput(
            SeatForecastDesignMatrix.of(input).toArray(),
            ModelRoute.of(input.stops().upstreamRouteId()),
            input.target().distance().stopCount(),
            input.target().remainingSeats(),
            input.maximumSeatsEverObserved(),
            null));
    }
}
