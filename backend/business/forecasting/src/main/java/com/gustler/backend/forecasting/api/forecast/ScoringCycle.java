package com.gustler.backend.forecasting.api.forecast;

/**
 * 채점의 주기 작업이다. 실행하는 앱(worker)이 기동 직후와 몇 초마다 부른다.
 *
 * <p>모든 메서드는 예외를 던지지 않는다. 실패는 로그와 지표로 남긴다.
 */
public interface ScoringCycle {
    /**
     * 최근 예보가 있는 노선을 모두 한 번 되감는 일을 채점 줄에 넣는다. 기동이 끝난 뒤 한 번 부른다.
     *
     * @return 줄에 넣었으면 참
     */
    boolean start();

    /**
     * 사건을 버린 노선을 되감고 오래 배치가 없는 노선의 마감을 닫는 일을 채점 줄에 넣는다.
     *
     * @return 줄에 넣었으면 참. 줄이 가득 찼으면 거짓이고 이번 회차는 건너뛴다
     */
    boolean tick();

    /**
     * 모아 둔 정답·미해결 줄을 쓴다. 실패하면 줄을 남겨 두고 다음 회차에 다시 쓴다.
     *
     * @return 새로 넣은 줄 수. 실패했으면 0
     */
    int flush();
}
