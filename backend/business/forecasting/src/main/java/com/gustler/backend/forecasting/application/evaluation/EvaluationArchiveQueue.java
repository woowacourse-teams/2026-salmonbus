package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 완료 정산의 이관 후보·재개 대상·재시도 시각을 관리한다.
 * 바뀐 원본의 예약은 재사용하지 않는다.
 */
public interface EvaluationArchiveQueue {
    boolean available();

    /**
     * 재개 가능한 배치 식별자를 찾는다. 삭제 허용 여부에 맞춰 진행 가능한 작업만 선택한다.
     */
    Optional<UUID> resumable(boolean deleteEnabled);

    /**
     * 최대 limit건의 아카이브 후보를 찾는다. 후보가 없으면 빈 값이며 소유권 예약은 별도 연산이다.
     */
    Optional<Candidates> next(int limit);

    /**
     * 소유 후 원본이 바뀐 배치를 폐기하고 폐기 여부를 반환한다. 변경된 원본으로 기존 아카이브를 확정하지 않는다.
     */
    boolean retireChanged(EvaluationArchiveBatch owned);

    /**
     * 현재 소유한 아카이브 배치를 나중에 재시도하도록 연기한다.
     */
    void defer(EvaluationArchiveBatch owned);
    record Candidates(long routeVersionId, List<Key> keys) {
        /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
        public Candidates {
            if (keys != null) {
                keys = Collections.unmodifiableList(new ArrayList<>(keys));
            }
        }
    }
}
