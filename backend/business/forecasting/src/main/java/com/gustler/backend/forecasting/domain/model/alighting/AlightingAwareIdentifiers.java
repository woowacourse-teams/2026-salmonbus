package com.gustler.backend.forecasting.domain.model.alighting;

/** 이전 접두({@code les-}, {@code LES_})로 적힌 번들·정산 근거의 식별자를 지금 이름으로 읽게 한다. */
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
