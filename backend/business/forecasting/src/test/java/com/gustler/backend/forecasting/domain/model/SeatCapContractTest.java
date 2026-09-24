package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 좌석 상한을 도메인과 품질 view 양쪽에서 같은 수로 고정한다.
 *
 * <p>잔여석이 격자를 벗어난 관측은 예보에 쓰지 않는다. 그 판정이 두 곳에 있다. 계산 경로는
 * {@link SeatGrid#LARGEST_SEATS}를 쓰고, 품질 view 는 SQL 리터럴로 같은 수를 적는다. view 는
 * 자바 상수를 읽을 수 없으므로 한쪽만 바꾸면 조용히 어긋난다. 상한을 올리면 view 가 거른 관측이
 * 계산으로 들어오고, 내리면 계산이 받아들일 관측을 view 가 미리 지운다.
 *
 * <p>팀 Confluence 의 백엔드 DDD 전면 재설계 문서가 "새 판정을 view 에 더할 때는 도메인 쪽에
 * 같은 규칙이 있는지 먼저 확인한다"고 적었다. 확인을 사람에게 맡기지 않고 빌드에서 대조한다.
 */
class SeatCapContractTest {

    private static final String CUTOVER = "db/migration/V18__ddd_storage_cutover.sql";
    private static final Pattern SEAT_CAP = Pattern.compile("remaining_seats\\s*<=\\s*(\\d+)");
    private static final int VIEW_OCCURRENCES = 2;

    @Test
    void 품질_view_의_좌석_상한은_도메인_격자와_같다() {
        // given
        List<Integer> caps = seatCapsIn(cutoverMigration());

        // then
        assertThat(caps)
            .as("상한을 적은 곳을 못 찾았다. view 가 바뀌었으면 이 검사도 함께 고친다")
            .hasSize(VIEW_OCCURRENCES);
        assertThat(caps)
            .as("view 의 좌석 상한이 SeatGrid.LARGEST_SEATS 와 달라지면 예보와 품질 판정이 어긋난다")
            .containsOnly(SeatGrid.LARGEST_SEATS);
    }

    private static List<Integer> seatCapsIn(
        final String sql
    ) {
        Matcher matcher = SEAT_CAP.matcher(sql);
        return matcher.results().map(result -> Integer.parseInt(result.group(1))).toList();
    }

    private static String cutoverMigration() {
        try (InputStream source = SeatCapContractTest.class.getClassLoader().getResourceAsStream(CUTOVER)) {
            assertThat(source).as("%s 를 클래스패스에서 못 찾았다", CUTOVER).isNotNull();
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new IllegalStateException("%s 를 읽지 못했다".formatted(CUTOVER), cause);
        }
    }
}
