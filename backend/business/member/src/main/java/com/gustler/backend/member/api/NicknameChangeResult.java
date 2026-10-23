package com.gustler.backend.member.api;

/** 닉네임을 바꾼 결과. 실패해도 예외 없이 이유를 결과로 알린다. */
public sealed interface NicknameChangeResult {
    /** 바뀐 뒤의 회원 정보. */
    record Changed(MemberProfile profile) implements NicknameChangeResult {
    }

    /** 닉네임 규칙을 어겼다. 아무것도 바꾸지 않았다. */
    record Invalid(NicknameViolation violation) implements NicknameChangeResult {
    }

    /** 영문 대소문자를 구분하지 않고 같은 닉네임을 다른 회원이 쓰고 있다. */
    record Taken() implements NicknameChangeResult {
    }

    /** 탈퇴했거나 없는 회원이다. */
    record MemberNotFound() implements NicknameChangeResult {
    }
}
