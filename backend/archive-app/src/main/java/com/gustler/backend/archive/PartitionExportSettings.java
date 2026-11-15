package com.gustler.backend.archive;

import java.time.Duration;

/**
 * 날짜 파티션 내보내기의 설정값이다. 만들 때 값을 검사하고, 범위를 벗어나면 {@link IllegalArgumentException} 으로 기동을 막는다.
 *
 * @param dropEnabled 확인을 마친 파티션을 떼어 지울지다. 꺼져 있으면 내보내고 확인만 한다.
 * @param retentionDays DB 에 남기는 한국 날짜 수(오늘 포함)다. {@code source_date <= 오늘 - retentionDays} 인 날짜가 대상이다.
 *     {@link #MIN_RETENTION_DAYS}~{@link #MAX_RETENTION_DAYS}일.
 * @param prefix S3 키 접두다.
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
    /**
     * 다시 뜬 worker 가 날짜 파티션 테이블의 줄을 다시 읽는 가장 긴 범위다. 이 범위 안의 줄을 지우면 다시 뜬 채점이 닫지 못한 예측에
     * 정답을 붙이지 못하거나 이미 내보낸 날짜의 줄을 다시 쓴다.
     *
     * <p>이관 앱은 예보 모듈에 의존하지 않아 값을 여기에 적는다. forecasting 모듈의 다음 값과 맞물리며, 그 값을 늘리면 이 값도 늘린다.
     * <ul>
     * <li>{@code forecast_run}: 채점 되감기({@code ScoringReplay})는 예보를 최대 26시간({@code DEFAULT_MAX_FORECAST_WINDOW}) 앞까지
     * 읽고, 수집 사실은 그 시각이 속한 한국 날짜의 00시에서 10분({@code INTAKE_MARGIN_MINUTES}) 앞까지 읽는다. 26시간 + 24시간 +
     * 10분으로 가장 길다.</li>
     * <li>{@code stop_pass}: 채점 되감기는 예보 범위의 시작에서 {@code DEFAULT_PASS_WINDOW}(26시간) -
     * {@code ScoringStage.IDLE_EXPIRY}(2시간 10분) 더 앞까지 읽는다. 49시간 50분이다.</li>
     * <li>{@code forecast_record}, {@code pass_label}, {@code forecast_unresolved}: 채점 되감기의 예보 범위 26시간이다.</li>
     * <li>{@code observation_batch}, {@code vehicle_observation}: 예보 되감기({@code RouteStateReplayer})의 늘린 창 상한
     * {@code MAXIMUM_EXTENDED_WINDOW}(12시간)에 착수 여유 90초를 더한 범위다.</li>
     * </ul>
     * 범위는 수신·닫힌 시각으로 재고 파티션은 그보다 조금 이른 예약·저장 시각의 날짜로 나뉘지만, 그 차이는
     * {@link #minimumRetentionDays} 가 날짜로 올림하며 남기는 폭보다 훨씬 짧다.
     */
    static final Duration LONGEST_REPLAY = Duration.ofHours(26).plusDays(1).plusMinutes(10);
    static final int MIN_RETENTION_DAYS = minimumRetentionDays(LONGEST_REPLAY);
    static final int MAX_RETENTION_DAYS = 30;

    public PartitionExportSettings {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                "보관 날짜 수는 %d~%d일이어야 한다".formatted(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS));
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

    /**
     * {@code replay} 범위 안의 줄을 지우지 않는 가장 작은 보관 날짜 수다.
     *
     * <p>보관 날짜 수가 n 이면 지우는 가장 늦은 파티션은 오늘 - n 이고, 그 파티션의 가장 늦은 줄은 (오늘 - n + 1)일 00시 직전의 것이다.
     * 지우는 줄이 지금과 가장 가까운 때는 날짜가 막 바뀐 오늘 00시이고 그때의 거리가 (n - 1)일이다. 이 거리가 범위 이상이어야 하므로
     * 범위를 날짜로 올림한 값에 하루를 더한다.
     */
    static int minimumRetentionDays(Duration replay) {
        final long wholeDays = replay.plusDays(1).minusNanos(1).toDays();
        return (int) wholeDays + 1;
    }

    private static boolean isUnusablePrefix(String prefix) {
        return prefix == null || !prefix.matches("[a-zA-Z0-9/_=-]+/") || prefix.startsWith("/")
            || prefix.startsWith("salmonbus-be/");
    }
}
