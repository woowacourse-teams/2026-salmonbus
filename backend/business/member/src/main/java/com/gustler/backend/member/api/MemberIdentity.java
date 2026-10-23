package com.gustler.backend.member.api;

/** 회원이 로그인에 쓰는 제공자 계정. 탈퇴 때 다시 인증한 계정이 이 회원의 것인지 확인하고 제공자 연결을 끊는 데 쓴다. */
public record MemberIdentity(LoginProvider provider, String subject) {
}
