package com.gustler.backend.stations.api;

/**
 * worker 스케줄러가 부르는 공개 기능. 한 번 부를 때 탐색점 하나만 처리하고 돌아온다.
 */
public interface FillStationTiles {

    Step fillNext();

    /**
     * 이번 실행 결과. 로그·관측용이다.
     */
    enum Step {

        PROBED, TILE_COMPLETED, NOTHING_TO_DO, PAUSED, FAILED
    }
}
