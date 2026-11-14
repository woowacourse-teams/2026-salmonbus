package com.gustler.backend.api.board;

import java.util.Locale;

/**
 * 이용자 API가 좌석 예보를 읽을 출처 설정({@code board.forecast-source})이다.
 *
 * <p>설정 값은 소문자 이름({@code legacy}, {@code record}, {@code auto})이다. 다른 값은 받지 않으며, 읽는 쪽이 기동할 때
 * {@link #of(String)}로 바꾸므로 잘못된 값이면 앱이 뜨지 않는다.
 */
public enum ForecastSourceSetting {
    /** 예측 한 줄씩 저장된 {@code seat_forecast}와 발행 표시 {@code forecast_publication}을 읽는다. 기본값이다. */
    LEGACY,
    /** 배치당 한 줄인 {@code forecast_run}과 버스당 한 줄인 {@code forecast_record}를 읽는다. */
    RECORD,
    /** 노선 판본마다 두 출처의 최신 배치를 견줘 배치 번호가 큰 쪽을 읽는다. 같으면 {@link #LEGACY}다. */
    AUTO;

    /**
     * 설정 문자열을 출처로 바꾼다. 앞뒤 공백과 대소문자는 가리지 않는다.
     *
     * @param value 설정 값
     * @return 설정한 출처
     * @throws IllegalArgumentException 값이 없거나 세 이름 중 하나가 아닐 때
     */
    public static ForecastSourceSetting of(String value) {
        if (value != null) {
            String name = value.strip().toUpperCase(Locale.ROOT);
            for (ForecastSourceSetting setting : values()) {
                if (setting.name().equals(name)) {
                    return setting;
                }
            }
        }
        throw new IllegalArgumentException(
            "board.forecast-source는 legacy, record, auto 중 하나여야 합니다: " + value);
    }
}
