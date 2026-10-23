package com.gustler.backend.member.domain;

import java.time.OffsetDateTime;

/** 실패를 하나 더 기록한 뒤의 연결 해제 요청. {@code attempts} 는 이번 실패까지 센 횟수다. */
public record UnlinkFailure(int attempts, UnlinkStatus status, OffsetDateTime nextAttemptAt) {
}
