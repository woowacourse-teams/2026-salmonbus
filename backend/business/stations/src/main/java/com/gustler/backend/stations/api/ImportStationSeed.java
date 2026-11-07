package com.gustler.backend.stations.api;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 공식 CSV(또는 gzip CSV)를 검증하고 기초 명부를 원자적으로 설치한다.
 */
public interface ImportStationSeed {

    /**
     * 다른 인스턴스가 설치 중이면 기다리지 않고 빈 값을 돌려준다.
     */
    Optional<Result> importFile(Path path, Charset charset);

    record Result(LocalDate dataDate, int stationCount, boolean changed) {
    }
}
