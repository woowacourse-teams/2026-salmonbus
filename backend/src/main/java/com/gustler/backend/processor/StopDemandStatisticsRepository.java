package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;

/**
 * 셀 통계를 읽고 쓰는 포트.
 *
 * <p><b>셀 하나만 집어 오는 길을 두지 않는다.</b> z화는 평균과 표준편차를 저장하지 않고 같은 세대의
 * 행들에서 그때 유도하고, 이웃 폴백은 반경 4 안의 다른 행이 손에 있어야 하며, 구간합은 통과 구간의
 * 행들을 합쳐 읽는다. 셋이 한 값 안에서 닫혀야 그 위에서 모델이 순수 함수가 된다.
 */
public interface StopDemandStatisticsRepository {

    /** 한 노선 판본 · 한 시간대의 셀 전부. */
    StopDemandStatistics read(
        long routeVersionId,
        TimeSlot timeSlot,
        String calculationVersion
    );

    /** 지금 세대 번호. 한 번도 안 돌았으면 0 이다. */
    int currentRevision(
        long routeVersionId,
        String calculationVersion
    );

    /**
     * 회수된 라벨을 (정류장, 한 시각) 으로 묶어 더한 값.
     *
     * <p>승차할 수 있는 정류장만 나온다. 그리고 <b>지평 1 짜리 예보만 센다.</b>
     * 한 통과 사건이 지평 열둘에 걸쳐 예보 행 열둘을 만들기 때문에, 안 거르면 같은 사건이 열두 번 세어진다.
     */
    List<StopDemandHourlyTotals> readHourlyTotals(
        long routeVersionId,
        Instant dataUntil
    );

    /** 한 세대를 덮어쓴다. */
    void replace(
        StopDemandGeneration generation
    );
}
