package com.gustler.backend.member.api;

/**
 * 제공자가 인증한 계정.
 *
 * <p>{@code subject} 는 제공자가 계정마다 발급한 고유 식별자이며 {@code (provider, subject)} 가 회원을 찾는 유일한 열쇠다.
 * 제공자가 알려 주는 닉네임 같은 프로필 정보는 받지 않는다.
 */
public record SocialIdentity(LoginProvider provider, String subject) {
}
