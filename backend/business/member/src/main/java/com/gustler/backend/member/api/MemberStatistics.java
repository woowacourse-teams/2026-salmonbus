package com.gustler.backend.member.api;

/** 현재 회원 상태를 낮은 주기의 운영 표본으로 읽는다. */
public interface MemberStatistics {
    long currentMembers();
}
