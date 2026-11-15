package com.gustler.backend.forecasting.application.forecast;

import java.util.List;

/**
 * 통과 정답({@code pass_label})과 정답을 받지 못한 예측({@code forecast_unresolved})을 저장한다.
 *
 * <p>쓰기는 호출하는 쪽이 연 트랜잭션 안에서만 하며 여러 줄을 한 문장으로 넣고 이미 있는 키를 건너뛴다.
 * {@code route} 행 잠금을 잡지 않는다.
 */
public interface PassLabelStore {
    /**
     * 통과 정답을 넣는다.
     *
     * @param labels 저장할 정답
     * @return 새로 넣은 정답. 받은 객체를 받은 순서로 돌려준다. 이미 있던 키의 줄과, 목록 안에서 같은 키로 뒤에 다시 나온 줄은 빠진다
     */
    List<PassLabel> saveLabels(List<PassLabel> labels);

    /**
     * 정답을 받지 못한 예측을 넣는다.
     *
     * @param unresolved 저장할 미해결 예측
     * @return 새로 넣은 미해결 예측. 받은 객체를 받은 순서로 돌려준다. 이미 있던 키의 줄과, 목록 안에서 같은 키로 뒤에 다시 나온 줄은
     *     빠진다
     */
    List<UnresolvedForecast> saveUnresolved(List<UnresolvedForecast> unresolved);
}
