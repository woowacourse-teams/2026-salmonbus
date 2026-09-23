package com.gustler.backend.quota.domain;

/**
 * 호출 횟수를 예약할 API별 한도.
 *
 * <p>한도는 서비스키 전체가 아니라 공공데이터포털에서 활용신청한 API마다 따로 적용된다.
 * 위치정보와 노선정보의 호출 횟수는 각각의 한도에서 차감한다.
 */
public enum CallQuota {

    /** 버스 위치정보 조회. 노선마다 90초에 한 번이라 한도를 쓰는 것은 사실상 이쪽뿐이다. */
    BUS_LOCATION("GBIS"),
    /** 버스 노선정보 조회. 노선당 하루 한 번이라 한도 대비 사용량이 적다. SAL-88에서 쓴다. */
    BUS_ROUTE("GBIS"),
    ;

    private final String provider;

    CallQuota(
        String provider
    ) {
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }

    /** 상수 이름을 그대로 쓴다. daily_call_quota.api_service 에 이 글자가 들어간다. */
    public String apiService() {
        return name();
    }
}
