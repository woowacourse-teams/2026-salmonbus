package com.gustler.backend.worker.scheduling;

import java.time.Duration;

/** 정상 종료 때 받은 일을 끝까지 처리하고 닫을 수 있는 실행 줄이다. */
public interface DrainableQueue {
    /**
     * 새 일을 받지 않고, 실행 중인 일과 기다리는 일이 모두 끝나기를 한도까지 기다린다. 한도가 지나면 기다리던 일을 버리고 실행 중인
     * 일에 중단을 요청한 뒤 돌아온다. 부른 뒤에는 다시 일을 받지 않는다. 예외를 던지지 않는다.
     *
     * @param timeout 기다리는 한도. 0이면 기다리지 않는다
     * @return 한도 때문에 끝내지 못한 일의 수
     */
    QueueDrain drain(Duration timeout);
}
