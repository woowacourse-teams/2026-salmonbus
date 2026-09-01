package com.gustler.backend.collector;

/**
 * 어느 한도에서 자리를 잡나.
 *
 * <p>한도는 서비스키 하나에 통째로 걸리는 것이 아니라 공공데이터포털에서 활용신청한 API 마다 따로 걸린다.
 * 그래서 위치정보와 노선정보가 서로의 자리를 깎지 않는다.
 */
public enum CallQuota {

    /** 버스 위치정보 조회. 노선마다 90초에 한 번이라 한도를 쓰는 것은 사실상 이쪽뿐이다. */
    BUS_LOCATION("GBIS"),
    /** 버스 노선정보 조회. 노선당 하루 한 번이라 한도 옆에서 무시할 수 있다. SAL-88 이 쓴다. */
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
