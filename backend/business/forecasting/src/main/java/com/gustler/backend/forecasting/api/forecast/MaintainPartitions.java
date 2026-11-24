package com.gustler.backend.forecasting.api.forecast;

import java.util.List;
import java.util.Map;

/**
 * 날짜 파티션 테이블의 파티션을 미리 만든다. worker가 기동할 때 {@link #prepare()}를 한 번, 그 뒤 주기적으로 {@link #maintain()}을
 * 부른다.
 *
 * <p>여러 인스턴스가 동시에 불러도 안전하다. 만들 권한이 없거나 기본 파티션에 그 날짜의 줄이 이미 있어 만들지 못해도 예외를 던지지
 * 않고 경고 로그와 지표만 남긴다. 그 날짜의 줄은 기본 파티션으로 들어가므로 쓰기는 멈추지 않는다.
 */
public interface MaintainPartitions {

    /**
     * 한국 날짜 기준 오늘부터 사흘 뒤까지 없는 날짜 파티션을 만든다. 기본 파티션의 줄 수는 세지 않는다. 줄 세기는 기본 파티션에 줄이
     * 쌓여 있으면 오래 걸리므로 기동을 붙잡지 않도록 주기 실행에 맡긴다.
     *
     * @return 이번 실행에서 만든 파티션과 만들지 못한 파티션. 기본 파티션 줄 수는 비어 있다
     */
    Report prepare();

    /**
     * 한국 날짜 기준 오늘부터 사흘 뒤까지 없는 날짜 파티션을 만들고 기본 파티션의 줄 수를 센다.
     *
     * @return 이번 실행에서 만든 파티션, 만들지 못한 파티션, 테이블별 기본 파티션 줄 수
     */
    Report maintain();

    /**
     * 유지 작업 한 번의 결과다.
     *
     * @param created 이번에 만든 파티션 이름
     * @param failed 만들지 못한 파티션 이름
     * @param defaultRows 테이블별 기본 파티션의 줄 수. 세지 못한 테이블은 빠진다
     */
    record Report(List<String> created, List<String> failed, Map<String, Long> defaultRows) {

        public Report {
            created = List.copyOf(created);
            failed = List.copyOf(failed);
            defaultRows = Map.copyOf(defaultRows);
        }
    }
}
