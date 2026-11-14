package com.gustler.backend.archive;

/**
 * 이관 작업을 실패가 아니라 보류로 끝낼 때 던진다. 이 예외가 나간 단계는 원본을 지우지 않았다.
 *
 * <p>사유 코드는 {@code operational_archive_job.failure_code} 와 보류 로그에 그대로 남는다.
 */
final class ArchiveDeferral extends RuntimeException {
    private final String reason;

    ArchiveDeferral(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
