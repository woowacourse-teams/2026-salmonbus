package com.gustler.backend.api.account.dto;

/** 닉네임이 없거나 비어 있으면 길이 규칙 위반으로 처리한다. */
public record UpdateNicknameRequest(String nickname) {
}
