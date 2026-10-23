package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Key;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Row;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import java.time.Instant;
import java.util.List;

/**
 * 검증된 이관 원본을 통계 표본으로 복원하고 재집계 중간값에 반영한다.
 */
public interface ArchivedDemandStatisticsStore {
    boolean hasArchive(long routeVersionId);

    List<Missing> missing(long routeVersionId, List<Long> observations);

    List<Sample> samples(DemandStatisticsRebuild rebuild, List<Long> observations, List<Row> archived);

    void add(DemandStatisticsRebuild rebuild, List<Total> totals);

    record Missing(Reference reference, Key key, String originalSha256) {
    }
    record Cell(String vehicleId, Instant hour, int stopOrder) {
    }
    record Sample(Cell cell, long arrivalSeats, long netBoarding) {
    }
    record Total(Cell cell, long count, long arrivalSeats, long netBoarding) {
        Total plus(Sample sample) {
            return new Total(cell, Math.incrementExact(count), Math.addExact(arrivalSeats, sample.arrivalSeats()),
                Math.addExact(netBoarding, sample.netBoarding()));
        }
    }
}
