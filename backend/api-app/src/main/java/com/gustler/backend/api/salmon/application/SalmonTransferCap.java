package com.gustler.backend.api.salmon.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 허용 환승 횟수. 한 번 갈아타는 이동까지 남긴다. */
@Component
public class SalmonTransferCap {
    private final int limit;

    public SalmonTransferCap(@Value("${salmon.action.max-transfers:1}") int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("max-transfers must be zero or positive");
        }
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    public boolean exceeded(int transfers) {
        return transfers > limit;
    }
}
