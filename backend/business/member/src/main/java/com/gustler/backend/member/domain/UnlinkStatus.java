package com.gustler.backend.member.domain;

/**
 * 저장된 연결 해제 요청의 상태. 상수 이름이 provider_unlink.status 에 그대로 저장된다.
 *
 * <p>해제에 성공한 요청은 행을 지우므로 성공 상태는 두지 않는다.
 */
public enum UnlinkStatus {
    /** 주기 작업이 다시 시도할 요청이다. */
    PENDING,
    /** 더 시도하지 않는다. 사람이 확인하도록 남긴 요청이다. */
    GAVE_UP,
}
