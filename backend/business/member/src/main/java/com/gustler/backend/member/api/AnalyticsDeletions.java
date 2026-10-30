package com.gustler.backend.member.api;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 탈퇴한 회원의 분석 기록 삭제 요청. 성공할 때까지 공개 ID와 시도 횟수만 보관한다. */
public interface AnalyticsDeletions {
    /** 회원 삭제와 같은 트랜잭션에서 호출한다. */
    void save(UUID publicId);

    /** 한 번에 최대 100명을 묶어 재시도한다. */
    void retry(Consumer<List<UUID>> send);

    /** 요청을 잠그고 전송한다. 다른 서버가 처리 중이거나 이미 성공했으면 건너뛴다. */
    void attempt(UUID publicId, Consumer<List<UUID>> send);
}
