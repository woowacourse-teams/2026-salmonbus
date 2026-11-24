package com.gustler.backend.forecasting.api.forecast;

/**
 * 정산의 주기 작업이다. 실행하는 앱(worker)이 기동 직후와 몇 초마다 호출한다.
 *
 * <p>모든 메서드는 예외를 던지지 않는다. 실패는 로그와 지표로 남긴다.
 */
public interface ScoringCycle {

    /**
     * 최근 예보가 있는 노선을 모두 한 번 복구하는 일을 정산 큐에 넣는다. 기동이 끝난 뒤 한 번 호출한다.
     *
     * @return 큐에 넣었으면 참
     */
    boolean start();

    /**
     * 이벤트를 버린 노선을 복구하고 오래 배치가 없는 노선의 마감을 닫는 일을 정산 큐에 넣는다.
     *
     * @return 큐에 넣었으면 참. 큐가 가득 찼으면 거짓이고 이번 회차는 건너뛴다
     */
    boolean tick();

    /**
     * 모아 둔 정답, 미해결 기록을 쓴다. 실패하면 저장할 행을 남겨 두고 다음 회차에 다시 쓴다.
     *
     * @return 새로 넣은 행 수. 실패했으면 0
     */
    int flush();
}
