package com.gustler.backend.member.domain;

/** 제시한 토큰의 세대가 저장된 계통의 현재 세대와 맞는 정도. */
public enum RefreshTokenStatus {
    /** 현재 세대다. */
    CURRENT,
    /** 한 세대 전이고 현재 세대를 발급한 지 유예 시간 안이다. */
    IN_GRACE,
    /** 오래된 세대다. 유예 시간이 지났거나 두 세대 이상 전이라 이미 쓴 토큰의 재사용으로 본다. */
    REUSED,
    /** 계통이 만료됐다. 세대와 상관없이 가장 먼저 판정한다. */
    EXPIRED,
    /** 현재보다 앞선 세대다. 발급한 적이 없다. */
    FUTURE,
}
