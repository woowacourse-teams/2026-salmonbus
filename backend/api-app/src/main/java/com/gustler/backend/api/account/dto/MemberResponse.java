package com.gustler.backend.api.account.dto;

import com.gustler.backend.member.api.MemberProfile;
import java.util.UUID;

/** {@code id} 는 공개용 UUID 다. */
public record MemberResponse(UUID id, String nickname, String provider) {
    public static MemberResponse from(MemberProfile profile) {
        return new MemberResponse(profile.publicId(), profile.nickname(), profile.provider().name());
    }
}
