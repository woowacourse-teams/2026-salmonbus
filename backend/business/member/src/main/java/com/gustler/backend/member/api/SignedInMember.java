package com.gustler.backend.member.api;

import java.util.UUID;

/**
 * 로그인을 마친 회원.
 *
 * <p>{@code publicId} 는 바깥에 내보내는 유일한 회원 식별자이며 액세스 토큰에도 이 값만 담는다.
 * {@code registered} 는 이번 로그인이 처음 인증이라 회원이 새로 만들어졌는지 나타낸다.
 */
public record SignedInMember(UUID publicId, boolean registered) {
}
