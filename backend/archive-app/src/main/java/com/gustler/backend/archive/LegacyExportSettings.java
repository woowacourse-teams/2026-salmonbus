package com.gustler.backend.archive;

import java.time.Duration;

/**
 * 옛 테이블 내보내기의 설정값이다. 만들 때 값을 검사하고, 범위를 벗어나면 {@link IllegalArgumentException} 으로 기동을 막는다.
 *
 * <p>읽는 속도는 {@code pageSize}, {@code pagePause}, {@code maxRowsPerRun}, {@code maxRunDuration}, {@code interval} 로 조절한다.
 * 한 회차는 줄 수나 시간 가운데 먼저 닿는 상한에서 멈추고, 그때까지 읽은 줄을 조각 하나로 닫아 장부에 적는다.
 *
 * @param prefix S3 키 접두. 줄 단위 이관과 날짜 파티션 내보내기의 접두와 서로 포함하지 않아야 한다.
 * @param interval 회차가 끝난 뒤 다음 회차까지 쉬는 시간. 10초~24시간.
 * @param pageSize 한 번에 읽는 줄 수. 1~10000줄.
 * @param pagePause 페이지 사이에 쉬는 시간. 0~60초. 쉬는 동안 DB 연결을 쥐지 않는다.
 * @param segmentBytes 조각 하나의 평문 크기 목표. 이 크기를 넘긴 페이지에서 조각을 닫는다. 64KiB~1GiB.
 * @param maxRowsPerRun 한 회차에 읽는 최대 줄 수. 1~10억 줄.
 * @param maxRunDuration 한 회차에 쓰는 최대 시간. 1초~6시간. 페이지와 조각 경계에서 확인한다.
 * @param statementTimeout 페이지 읽기·줄 세기 문장 하나의 제한 시간. 1초~10분.
 */
public record LegacyExportSettings(
    String prefix,
    Duration interval,
    int pageSize,
    Duration pagePause,
    long segmentBytes,
    long maxRowsPerRun,
    Duration maxRunDuration,
    Duration statementTimeout
) {
    static final long MIN_SEGMENT_BYTES = 64L * 1024;
    static final long MAX_SEGMENT_BYTES = 1024L * 1024 * 1024;
    static final long MAX_ROWS_PER_RUN = 1_000_000_000L;

    /**
     * @param lineArchivePrefix 줄 단위 이관의 S3 접두. 비교에만 쓴다.
     * @param partitionPrefix 날짜 파티션 내보내기의 S3 접두. 비교에만 쓴다.
     */
    public LegacyExportSettings(
        String prefix,
        Duration interval,
        int pageSize,
        Duration pagePause,
        long segmentBytes,
        long maxRowsPerRun,
        Duration maxRunDuration,
        Duration statementTimeout,
        String lineArchivePrefix,
        String partitionPrefix
    ) {
        this(prefix, interval, pageSize, pagePause, segmentBytes, maxRowsPerRun, maxRunDuration, statementTimeout);
        for (String other : new String[] { lineArchivePrefix, partitionPrefix }) {
            if (prefix.startsWith(other) || other.startsWith(prefix)) {
                throw new IllegalArgumentException("옛 테이블 내보내기 접두는 다른 이관 접두와 겹치지 않아야 한다");
            }
        }
    }

    public LegacyExportSettings {
        if (prefix == null || !prefix.matches("[a-zA-Z0-9/_=-]+/") || prefix.startsWith("/")
            || prefix.startsWith("salmonbus-be/")) {
            throw new IllegalArgumentException("옛 테이블 내보내기에는 새 S3 접두가 필요하다");
        }
        if (interval.compareTo(Duration.ofSeconds(10)) < 0 || interval.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("옛 테이블 내보내기 주기는 10초~24시간이어야 한다");
        }
        if (pageSize < 1 || pageSize > 10000) {
            throw new IllegalArgumentException("옛 테이블 내보내기 페이지는 1~10000줄이어야 한다");
        }
        if (pagePause.isNegative() || pagePause.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("페이지 사이 쉬는 시간은 0~60초여야 한다");
        }
        if (segmentBytes < MIN_SEGMENT_BYTES || segmentBytes > MAX_SEGMENT_BYTES) {
            throw new IllegalArgumentException("조각 크기는 64KiB~1GiB여야 한다");
        }
        if (maxRowsPerRun < 1 || maxRowsPerRun > MAX_ROWS_PER_RUN) {
            throw new IllegalArgumentException("회차당 줄 수는 1~10억 줄이어야 한다");
        }
        if (maxRunDuration.compareTo(Duration.ofSeconds(1)) < 0 || maxRunDuration.compareTo(Duration.ofHours(6)) > 0) {
            throw new IllegalArgumentException("회차당 시간은 1초~6시간이어야 한다");
        }
        if (statementTimeout.compareTo(Duration.ofSeconds(1)) < 0
            || statementTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("문장 제한 시간은 1초~10분이어야 한다");
        }
    }
}
