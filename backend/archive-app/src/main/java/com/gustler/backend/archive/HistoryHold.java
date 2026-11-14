package com.gustler.backend.archive;

import java.util.Locale;

/**
 * 하차 예보가 아직 읽어야 하는 최근 관측의 줄 단위 이관을 미루는 기준이다. 설정 {@code archive.history-hold}의 값이다.
 */
public enum HistoryHold {
    /**
     * 옛 예보 경로의 체크포인트 보호선({@code alighting_history_checkpoint.retain_from})을 본다. 기본값이며 옛 경로가 본선인 동안
     * 쓴다.
     */
    CHECKPOINT,
    /**
     * 체크포인트 표를 보지 않고, 날짜의 끝이 지금부터 {@code archive.history-hold-window} 전보다 뒤면 미룬다. 새 예보 경로가
     * 본선이 되어({@code forecast.pipeline=v2}) 체크포인트 표가 더 갱신되지 않을 때 쓴다. 창은 worker의 되감기 창
     * ({@code forecast.replay-window})보다 넉넉해야 한다.
     */
    WINDOW;

    /**
     * 설정 값을 읽는다. 대소문자는 가리지 않는다.
     *
     * @param value {@code checkpoint} 또는 {@code window}
     * @return 기준
     * @throws IllegalArgumentException 모르는 값이면 던진다
     */
    public static HistoryHold parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new IllegalArgumentException("archive.history-hold 는 checkpoint 또는 window 다: " + value);
        }
    }
}
