package com.gustler.backend.member.api;

/** 아직 끊지 못한 제공자 연결. {@code attempts} 는 지금까지 실패한 횟수다. */
public record PendingUnlink(long id, MemberIdentity identity, int attempts) {
}
