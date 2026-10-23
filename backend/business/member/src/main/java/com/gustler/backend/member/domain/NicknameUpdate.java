package com.gustler.backend.member.domain;

/** 저장소가 닉네임 변경을 처리한 결과. */
public enum NicknameUpdate {
    UPDATED,
    TAKEN,
    MEMBER_NOT_FOUND,
}
