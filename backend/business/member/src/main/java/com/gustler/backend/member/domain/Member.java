package com.gustler.backend.member.domain;

import java.util.UUID;

/**
 * 저장된 회원. {@code id} 는 내부 키라 바깥에 내보내지 않는다.
 *
 * <p>닉네임은 저장된 문자열 그대로 담는다. 금칙어가 나중에 늘어도 이미 가입한 회원이 로그인하지 못하면 안 된다.
 */
public record Member(long id, UUID publicId, String nickname) {
}
