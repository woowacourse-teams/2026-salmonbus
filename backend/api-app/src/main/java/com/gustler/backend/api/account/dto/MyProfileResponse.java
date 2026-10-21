package com.gustler.backend.api.account.dto;

import com.gustler.backend.member.api.MemberProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code id} 는 공개용 UUID 다. */
@Schema(requiredProperties = {"id", "nickname", "provider", "createdAt"})
public record MyProfileResponse(
    @Schema(description = "회원 공개 ID")
    UUID id,
    @Schema(description = "닉네임. 가입할 때 무작위로 정해지고 PATCH /api/me로 바꾼다")
    String nickname,
    @Schema(description = "로그인 제공자", allowableValues = {"KAKAO", "APPLE"})
    String provider,
    @Schema(description = "가입 시각")
    OffsetDateTime createdAt
) {
    public static MyProfileResponse from(MemberProfile profile) {
        return new MyProfileResponse(
            profile.publicId(), profile.nickname(), profile.provider().name(), profile.createdAt());
    }
}
