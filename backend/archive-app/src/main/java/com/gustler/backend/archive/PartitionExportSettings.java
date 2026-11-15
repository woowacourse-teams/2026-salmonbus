package com.gustler.backend.archive;

import java.time.Duration;

/**
 * 날짜 파티션 내보내기의 설정값이다. 만들 때 값을 검사하고, 범위를 벗어나면 {@link IllegalArgumentException} 으로 기동을 막는다.
 *
 * @param dropEnabled 확인을 마친 파티션을 떼어 지울지다. 꺼져 있으면 내보내고 확인만 한다.
 * @param retentionDays DB 에 남기는 한국 날짜 수(오늘 포함)다. {@code source_date <= 오늘 - retentionDays} 인 날짜가 대상이다. 2~30일.
 * @param prefix S3 키 접두다. 줄 단위 이관의 {@code archive.prefix} 와 서로 포함하지 않아야 한다.
 * @param interval 회차 사이 쉬는 시간이다. 1분~24시간.
 * @param pageSize 파티션을 한 번에 읽는 줄 수다. 1~10000줄.
 * @param lockTimeout 파티션을 떼어 지울 때 잠금을 기다리는 시간이다. 100ms~10초.
 */
public record PartitionExportSettings(
    boolean dropEnabled,
    int retentionDays,
    String prefix,
    Duration interval,
    int pageSize,
    Duration lockTimeout
) {
    static final int MIN_RETENTION_DAYS = 2;
    static final int MAX_RETENTION_DAYS = 30;

    /**
     * @param lineArchivePrefix 줄 단위 이관의 S3 접두다. 두 형식의 파일이 섞이지 않게 비교에만 쓴다.
     */
    public PartitionExportSettings(
        boolean dropEnabled,
        int retentionDays,
        String prefix,
        Duration interval,
        int pageSize,
        Duration lockTimeout,
        String lineArchivePrefix
    ) {
        this(dropEnabled, retentionDays, prefix, interval, pageSize, lockTimeout);
        if (prefix.startsWith(lineArchivePrefix) || lineArchivePrefix.startsWith(prefix)) {
            throw new IllegalArgumentException("날짜 내보내기 접두는 줄 단위 이관 접두와 겹치지 않아야 한다");
        }
    }

    public PartitionExportSettings {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("보관 날짜 수는 2~30일이어야 한다");
        }
        if (isUnusablePrefix(prefix)) {
            throw new IllegalArgumentException("날짜 내보내기에는 새 S3 접두가 필요하다");
        }
        if (interval.compareTo(Duration.ofMinutes(1)) < 0 || interval.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("날짜 내보내기 주기는 1분~24시간이어야 한다");
        }
        if (pageSize < 1 || pageSize > 10000) {
            throw new IllegalArgumentException("날짜 내보내기 페이지는 1~10000줄이어야 한다");
        }
        if (lockTimeout.compareTo(Duration.ofMillis(100)) < 0 || lockTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("파티션 지우기 잠금 대기는 100ms~10초여야 한다");
        }
    }

    private static boolean isUnusablePrefix(String prefix) {
        return prefix == null || !prefix.matches("[a-zA-Z0-9/_=-]+/") || prefix.startsWith("/")
            || prefix.startsWith("salmonbus-be/");
    }
}
