package com.gustler.backend.member.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 바깥에 보여 줄 수 있는 회원 정보. 내부 키와 제공자 식별자는 담지 않는다. */
public record MemberProfile(
    UUID publicId,
    String nickname,
    LoginProvider provider,
    OffsetDateTime createdAt
) {
}
