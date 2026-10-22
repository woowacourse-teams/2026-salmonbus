package com.gustler.backend.member.domain;

import java.util.UUID;

/** 서명을 확인한 토큰이 가리키는 로그인 계통과 세대. */
public record RefreshTokenClaim(UUID familyId, int generation) {
}
