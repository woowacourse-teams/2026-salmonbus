package com.gustler.backend.member.api;

/**
 * 제공자가 인증한 계정.
 *
 * <p>{@code subject} 는 제공자가 계정마다 발급한 고유 식별자이며 {@code (provider, subject)} 가 회원을 찾는 유일한 열쇠다.
 * {@code suggestedNickname} 은 제공자가 알려 준 닉네임이고 없으면 {@code null} 이다. 가입할 때 한 번만 쓰이고,
 * 닉네임 규칙에 맞지 않거나 이미 쓰이면 무시된다.
 */
public record SocialIdentity(LoginProvider provider, String subject, String suggestedNickname) {
}
