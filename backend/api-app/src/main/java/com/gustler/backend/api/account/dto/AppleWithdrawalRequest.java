package com.gustler.backend.api.account.dto;

/** 탈퇴를 마치거나 그만둔 뒤 돌아갈 경로. 비어 있거나 허용하지 않는 경로면 첫 화면으로 돌아간다. */
public record AppleWithdrawalRequest(String returnTo) {
}
