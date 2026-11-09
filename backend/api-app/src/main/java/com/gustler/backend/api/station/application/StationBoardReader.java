package com.gustler.backend.api.station.application;

import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReading;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteId;
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
 * <p>판정보드 화면과 같은 {@link BoardQueryService#readBoard}를 노선마다 차례로 불러, 오래됨·캐시 시간 정책을 판정보드와 같게 둔다.
 * 한 노선이 실패해도 다른 노선은 계속 읽는다. 읽기 합계가 상한을 넘으면 남은 노선은 판정보드 없이 둔다.
 */
@Component
class StationBoardReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(StationBoardReader.class);

    static final Duration BOARD_READ_BUDGET = Duration.ofMillis(800);

    private final BoardQueryService boards;

    StationBoardReader(BoardQueryService boards) {
        this.boards = boards;
    }

    Map<String, BoardLoad> read(List<String> routeIds) {
        final long deadline = System.nanoTime() + BOARD_READ_BUDGET.toNanos();
        Map<String, BoardLoad> loaded = new LinkedHashMap<>();
        for (String routeId : routeIds) {
            if (loaded.containsKey(routeId)) {
                continue;
            }
            if (System.nanoTime() > deadline) {
                loaded.put(routeId, BoardLoad.failed("BOARD_UNAVAILABLE"));
                continue;
            }
            loaded.put(routeId, load(routeId));
        }
        return loaded;
    }

    private BoardLoad load(String routeId) {
        try {
            BoardReading reading = boards.readBoard(new RouteId(routeId));
            return new BoardLoad(reading.board(), null);
        } catch (ApiException failure) {
            if (failure.code() == ErrorCode.MODEL_OUT_OF_SCOPE) {
                return BoardLoad.failed("PREPARING");
            }
            return BoardLoad.failed("BOARD_UNAVAILABLE");
        } catch (RuntimeException failure) {
            LOGGER.warn(
                "정류장 상세의 노선 보드 조회에 실패했습니다. routeId={} exceptionType={}",
                routeId,
                failure.getClass().getSimpleName()
            );
            return BoardLoad.failed("BOARD_UNAVAILABLE");
        }
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
