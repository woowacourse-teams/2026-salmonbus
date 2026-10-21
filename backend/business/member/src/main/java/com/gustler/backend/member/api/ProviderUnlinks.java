package com.gustler.backend.member.api;

import java.util.List;

/**
 * 탈퇴한 회원의 제공자 연결 해제 요청을 다시 시도할 수 있게 관리한다.
 *
 * <p>요청은 시도할 때마다 일정 시간 동안 시도하는 쪽 몫으로 잡힌다. 서버가 여러 대여도 같은 요청을 동시에 두 곳에서 시도하지
 * 않는다. 성공하면 요청과 함께 제공자 식별자도 지운다. 정해진 횟수를 넘겨 실패하면 더 시도하지 않고 사람이 확인하도록 남긴다.
 */
public interface ProviderUnlinks {
    /**
     * 지금 다시 시도할 요청을 {@code limit} 개까지 이 호출자 몫으로 잡아 돌려준다. 오래 기다린 요청부터 잡는다.
     * {@code limit} 이 1 보다 작으면 {@link IllegalArgumentException} 이다.
     */
    List<PendingUnlink> claimDue(int limit);

    /** 연결을 끊었다. 요청과 제공자 식별자를 지운다. 없는 요청이면 아무 일도 하지 않는다. */
    void succeeded(long id);

    /**
     * 실패를 기록한다. {@code reason} 은 사람이 읽는 짧은 원인이며 토큰이나 키를 담지 않는다. 200자를 넘으면 자른다.
     * 없는 요청이나 이미 포기한 요청이면 아무 일도 하지 않는다.
     */
    void failed(long id, String reason);

    /**
     * 포기한 지 보관 기간(30일)이 지난 요청을 한 묶음 지우고 지운 개수를 돌려준다. 돌려준 개수가 {@link #PURGE_BATCH_SIZE}
     * 와 같으면 더 남아 있을 수 있다.
     */
    int purgeGaveUp();

    /** {@link #purgeGaveUp()} 이 한 번에 지우는 최대 개수. */
    int PURGE_BATCH_SIZE = 1_000;
}
