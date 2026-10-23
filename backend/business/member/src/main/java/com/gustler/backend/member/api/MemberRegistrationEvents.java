package com.gustler.backend.member.api;

/** 회원 가입 결과를 사용자 식별값 없이 운영 계측으로 전달한다. */
public interface MemberRegistrationEvents {
    MemberRegistrationEvents NONE = new MemberRegistrationEvents() {
        @Override
        public void succeeded(LoginProvider provider) {
        }

        @Override
        public void failed(LoginProvider provider, Failure failure) {
        }
    };

    void succeeded(LoginProvider provider);

    void failed(LoginProvider provider, Failure failure);

    enum Failure {
        NICKNAME_EXHAUSTED,
        REPOSITORY
    }
}
