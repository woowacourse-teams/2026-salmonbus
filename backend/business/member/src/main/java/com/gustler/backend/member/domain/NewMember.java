package com.gustler.backend.member.domain;

import com.gustler.backend.member.api.LoginProvider;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 가입시킬 회원과 그 회원이 처음 인증한 제공자 계정. */
public record NewMember(
    UUID publicId,
    Nickname nickname,
    LoginProvider provider,
    String subject,
    OffsetDateTime joinedAt
) {
}
