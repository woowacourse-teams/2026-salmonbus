package com.gustler.backend.member.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 방금 세대를 올린 계통. */
public record RotatedRefreshToken(UUID publicId, int generation, OffsetDateTime expiresAt) {
}
