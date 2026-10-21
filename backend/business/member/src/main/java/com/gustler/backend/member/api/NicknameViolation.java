package com.gustler.backend.member.api;

/** 닉네임 규칙을 어긴 이유. */
public enum NicknameViolation {
    LENGTH,
    CHARACTERS,
    RESERVED,
}
