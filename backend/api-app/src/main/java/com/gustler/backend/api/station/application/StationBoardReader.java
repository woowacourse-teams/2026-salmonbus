package com.gustler.backend.api.station.application;

import com.gustler.backend.api.board.application.BoardBatchRead;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReadDeadline;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 정류장 상세가 쓰는 노선 판정보드들을 읽는다.
 *
 * <p>판정보드 화면과 같은 규칙으로 읽는 {@link BoardQueryService#readBoards}를 한 번 불러, 오래됨·캐시 시간 정책을 판정보드와
 * 같게 두면서 노선 수와 관계없이 연결 하나와 고정된 수의 SQL로 읽는다. 한 노선이 실패해도 다른 노선은 그대로 둔다.
 * 읽기 상한은 연결을 얻기 전부터 잰다. 스냅샷을 읽은 뒤 상한이 지났으면 캐시에 없는 노선은 요청 순서로 첫 노선만 읽고 나머지는
 * 판정보드 없이 둔다. 저장소 조회가 실패하면 모든 노선을 판정보드 없이 둔다.
 */
@Component
class StationBoardReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(StationBoardReader.class);

    static final Duration BOARD_READ_BUDGET = Duration.ofMillis(800);

    private static final String PREPARING = "PREPARING";
    private static final String BOARD_UNAVAILABLE = "BOARD_UNAVAILABLE";

    private final BoardQueryService boards;
    private final Counter budgetSkips;
    private final Counter readFailures;

    StationBoardReader(BoardQueryService boards, MeterRegistry meters) {
        this.boards = boards;
        this.budgetSkips = failureCounter(meters, "budget");
        this.readFailures = failureCounter(meters, "read");
    }

    private static Counter failureCounter(MeterRegistry meters, String cause) {
        return Counter.builder("salmonbus.station.detail.board.unavailable")
            .description("정류장 상세에서 판정보드 없이 낸 노선 수. 노선 준비 중(PREPARING)은 세지 않는다")
            .tag("cause", cause)
            .register(meters);
    }

    Map<String, BoardLoad> read(List<String> routeIds) {
        BoardReadDeadline deadline = BoardReadDeadline.after(BOARD_READ_BUDGET);
        Map<String, BoardLoad> loaded = new LinkedHashMap<>();
        if (routeIds.isEmpty()) {
            return loaded;
        }
        Map<String, BoardBatchRead> reads;
        try {
            reads = boards.readBoards(routeIds, deadline);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                "정류장 상세의 노선 보드 일괄 조회에 실패했습니다. routes={} exceptionType={}",
                routeIds.size(),
                failure.getClass().getSimpleName()
            );
            for (String routeId : routeIds) {
                if (loaded.putIfAbsent(routeId, BoardLoad.failed(BOARD_UNAVAILABLE)) == null) {
                    readFailures.increment();
                }
            }
            return loaded;
        }
        reads.forEach((routeId, read) -> loaded.put(routeId, loadOf(routeId, read)));
        return loaded;
    }

    private BoardLoad loadOf(String routeId, BoardBatchRead read) {
        if (read.reading() != null) {
            return new BoardLoad(read.reading().board(), null);
        }
        if (read.skipped()) {
            budgetSkips.increment();
            return BoardLoad.failed(BOARD_UNAVAILABLE);
        }
        RuntimeException failure = read.failure();
        if (failure instanceof ApiException apiFailure) {
            if (apiFailure.code() == ErrorCode.MODEL_OUT_OF_SCOPE) {
                return BoardLoad.failed(PREPARING);
            }
            readFailures.increment();
            return BoardLoad.failed(BOARD_UNAVAILABLE);
        }
        LOGGER.warn(
            "정류장 상세의 노선 보드 조회에 실패했습니다. routeId={} exceptionType={}",
            routeId,
            failure.getClass().getSimpleName()
        );
        readFailures.increment();
        return BoardLoad.failed(BOARD_UNAVAILABLE);
    }

    /**
     * 판정보드 또는 실패 사유.
     */
    record BoardLoad(Board board, String failure) {

        static BoardLoad failed(String reason) {
            return new BoardLoad(null, reason);
        }
    }
}
