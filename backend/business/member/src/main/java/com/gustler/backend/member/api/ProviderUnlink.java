package com.gustler.backend.member.api;

/** 탈퇴하는 회원의 제공자 연결을 끊는 일이 어디까지 됐는지. */
public enum ProviderUnlink {
    /** 아직 끊지 않았다. 탈퇴와 함께 끊을 요청을 기록하고, 호출자가 곧바로 한 번 시도한 뒤 실패하면 주기 작업이 다시 시도한다. */
    PENDING,
    /** 호출자가 탈퇴 전에 이미 끊었다. 기록하지 않는다. */
    DONE,
}
