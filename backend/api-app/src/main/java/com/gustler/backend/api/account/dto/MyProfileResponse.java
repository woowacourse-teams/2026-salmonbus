package com.gustler.backend.api.account.dto;

import com.gustler.backend.member.api.MemberProfile;
import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code id} 는 공개용 UUID 다. */
public record MyProfileResponse(UUID id, String nickname, String provider, OffsetDateTime createdAt) {
    public static MyProfileResponse from(MemberProfile profile) {
        return new MyProfileResponse(
            profile.publicId(), profile.nickname(), profile.provider().name(), profile.createdAt());
    }
}
