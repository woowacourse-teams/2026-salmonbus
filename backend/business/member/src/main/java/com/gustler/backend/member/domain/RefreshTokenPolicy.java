package com.gustler.backend.member.domain;

import java.time.Duration;

/** 리프레시 토큰의 수명, 최대 유지 시간, 재제시 유예 시간. */
public final class RefreshTokenPolicy {
    /**
     * 현재 세대의 만료까지의 시간이다. 갱신할 때마다 다시 30일을 받으므로 쓰는 동안은 로그인이 이어지고,
     * 30일 동안 쓰지 않으면 다시 로그인해야 한다. 다시 받는 만료도 처음 로그인한 뒤 {@link #MAX_LIFETIME} 을 넘지 않는다.
     */
    public static final Duration LIFETIME = Duration.ofDays(30);

    /**
     * 처음 로그인한 뒤 계통이 유지되는 최대 시간이다. 계속 갱신해도 처음 로그인한 뒤 180일이 지나면 다시 로그인해야 한다.
     * 탈취된 토큰을 갱신만으로 끝없이 이어 쓰지 못하게 둔다.
     */
    public static final Duration MAX_LIFETIME = Duration.ofDays(180);

    /**
     * 한 세대 전 토큰을 다시 받아 줄 시간이다. 한 사용자의 탭 두 개가 같은 토큰으로 동시에 갱신하거나 응답이 유실되어
     * 재시도할 때, 늦게 온 요청을 탈취로 오인해 계통 전체를 폐기하지 않기 위해 둔다. 이 안에서는 상태를 바꾸지 않고
     * 먼저 갱신한 쪽이 받은 것과 같은 현재 토큰을 돌려준다.
     * 길어질수록 탈취된 토큰이 통하는 시간도 길어지므로 네트워크 재시도를 견디는 정도로만 짧게 잡는다.
     * 갱신 시각과 지금을 모두 앱의 시계로 재므로, API 서버가 여러 대이면 시계가 10초 안으로 맞춰져 있어야 한다.
     */
    public static final Duration GRACE = Duration.ofSeconds(10);

    private RefreshTokenPolicy() {
    }
}
