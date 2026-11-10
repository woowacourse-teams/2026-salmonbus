package com.gustler.backend.forecasting.domain.model.alighting;

/** 과거에 발행한 번들·체크포인트·정산 계약 식별자의 읽기 호환만 제공한다. */
public final class AlightingAwareIdentifiers {
    private AlightingAwareIdentifiers() {
    }

    public static String canonical(String value) {
        if (value != null && value.startsWith("les-")) {
            return "alighting-" + value.substring(4);
        }
        if (value != null && value.startsWith("LES_")) {
            return "ALIGHTING_AWARE_" + value.substring(4);
        }
        return value;
    }
}
