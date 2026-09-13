package com.gustler.backend.processor;

import com.gustler.backend.processor.seatdistribution.RuntimeSnapshot;
import java.util.Optional;

/**
 * 지금 예보에 쓸 것을 한 덩어리로 내는 포트.
 *
 * <p>예보 배치가 도는 배포와 모델을 <b>따로 읽지 않게</b> 하는 자리다. 따로 읽으면 승격이 일어나는
 * 순간에 배포 행은 B 인데 계산은 A 계수로 하고 DB 에는 B 예보라고 적는 상태가 된다.
 *
 * <p>배치 하나는 이것을 한 번 받아 끝까지 쓴다. 그래서 그 배치의 모든 예보 행이 같은 계수에서 나온다.
 */
public interface ForecastRuntime {

    /** 쓸 것이 없으면 비어 있다. 계수가 아직 없거나 배포와 계수의 신원이 안 맞을 때다. */
    Optional<RuntimeSnapshot> resolveActive();
}
