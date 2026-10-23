package com.gustler.backend.member.api;

/** 소셜 계정으로 로그인한다. 처음 인증한 계정이면 회원으로 가입시킨다. */
public interface SignInWithSocialAccount {
    /**
     * 같은 계정이 동시에 처음 로그인해도 회원은 한 명만 만들어지고 두 호출 모두 그 회원을 돌려받는다.
     */
    SignedInMember signIn(SocialIdentity identity);
}
