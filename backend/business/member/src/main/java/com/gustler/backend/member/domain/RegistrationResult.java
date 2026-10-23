package com.gustler.backend.member.domain;

/** 가입을 시도한 결과. 어떤 경우에도 저장소에 회원이 반쯤 만들어진 채로 남지 않는다. */
public sealed interface RegistrationResult {
    /** 회원과 제공자 계정이 함께 저장됐다. */
    record Registered(Member member) implements RegistrationResult {
    }

    /** 같은 닉네임을 이미 다른 회원이 쓰고 있다. 아무것도 저장하지 않았다. */
    record NicknameTaken() implements RegistrationResult {
    }

    /** 다른 요청이 같은 제공자 계정을 먼저 가입시켰다. 아무것도 저장하지 않았다. */
    record AlreadyRegistered() implements RegistrationResult {
    }
}
