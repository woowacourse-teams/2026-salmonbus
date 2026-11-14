package com.gustler.backend.api.board.application;

/**
 * 탑승판 스냅샷이 예보를 읽어 온 저장 출처다. 같은 관측 배치라도 출처가 다르면 다른 스냅샷으로 본다.
 */
public enum ForecastSource {
    /** {@code forecast_publication}과 {@code seat_forecast}. */
    LEGACY,
    /** {@code forecast_run}과 {@code forecast_record}. */
    RECORD
}
