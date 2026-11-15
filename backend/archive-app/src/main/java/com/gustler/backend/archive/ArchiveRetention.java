package com.gustler.backend.archive;

import java.time.ZoneId;

/** 보관 날짜를 가르는 시간대다. 날짜는 한국 날짜로 세고 고정 시간 길이로 계산하지 않는다. */
public final class ArchiveRetention {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ArchiveRetention() {
    }
}
