package com.gustler.backend.forecasting.domain.statistics;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild.ScanWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class StatisticsProgressBoundaryTest {
    private static final Instant UNTIL = Instant.parse("2026-10-10T00:00:00Z");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000223");

    @Test
    void 시계가_뒤로_움직여도_재시작은_입력_시각_상한을_낮추지_않고_커서를_초기화한다() {
        // given
        DemandStatisticsRun run = new DemandStatisticsRun(1, REQUEST, 1, DemandStatisticsRun.Phase.DONE,
            UNTIL, 100, "vehicle", 90, new DemandStatisticsRun.FoldCursor("vehicle", UNTIL, 4),
            new DemandStatisticsRun.ReduceCursor(4, TimeSlot.MORNING, LocalDate.of(2026, 10, 10)), UNTIL);
        UUID next = UUID.fromString("00000000-0000-0000-0000-000000000224");

        // when
        run.restart(next, 2, UNTIL.minusSeconds(1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(run.runId()).isEqualTo(next);
            softly.assertThat(run.qualityRevision()).isEqualTo(2);
            softly.assertThat(run.phase()).isEqualTo(DemandStatisticsRun.Phase.CLEAN);
            softly.assertThat(run.dataUntil()).isEqualTo(UNTIL);
            softly.assertThat(run.inputUntilId()).isZero();
            softly.assertThat(run.vehicleCursor()).isEmpty();
            softly.assertThat(run.inputCursor()).isZero();
            softly.assertThat(run.foldCursor()).isEmpty();
            softly.assertThat(run.reduceCursor()).isEmpty();
        });
    }

    @Test
    void 빈_그룹_페이지는_그룹만_닫고_다음_그룹의_부재는_SCAN을_끝낸다() {
        // given
        DemandStatisticsRebuild emptyPage = grouped();
        DemandStatisticsRebuild absentGroup = grouped();
        emptyPage.groupOpened(UNTIL, 32);

        // when
        emptyPage.scannedGroup(List.of());
        absentGroup.noGroupLeft();

        // then
        assertSoftly(softly -> {
            softly.assertThat(emptyPage.phase()).isEqualTo(DemandStatisticsRebuild.Phase.SCAN);
            softly.assertThat(emptyPage.cursorId()).isZero();
            softly.assertThat(emptyPage.scan()).contains(new ScanWindow(50, UNTIL, 32, null, 0));
            softly.assertThat(absentGroup.phase()).isEqualTo(DemandStatisticsRebuild.Phase.CLEAR);
            softly.assertThat(absentGroup.cursorId()).isZero();
        });
    }

    @Test
    void 정확히_한_페이지를_읽으면_그룹과_레거시_커서를_모두_다음_빈_조회까지_유지한다() {
        // given
        DemandStatisticsRebuild legacy = new DemandStatisticsRebuild(1, RebuildScope.wholeRoute(), REQUEST,
            1, UNTIL, 100, 200, 0, DemandStatisticsRebuild.Phase.SCAN, null);
        DemandStatisticsRebuild grouped = grouped();
        grouped.groupOpened(UNTIL, 32);
        List<Long> ids = LongStream.rangeClosed(1, DemandStatisticsRebuild.PAGE_SIZE).boxed().toList();

        // when
        legacy.scannedObservations(ids);
        grouped.scannedGroup(ids);

        // then
        assertSoftly(softly -> {
            softly.assertThat(legacy.phase()).isEqualTo(DemandStatisticsRebuild.Phase.SCAN);
            softly.assertThat(legacy.cursorId()).isEqualTo(128);
            softly.assertThat(grouped.phase()).isEqualTo(DemandStatisticsRebuild.Phase.SCAN);
            softly.assertThat(grouped.cursorId()).isEqualTo(128);
            softly.assertThat(grouped.scan().orElseThrow().hasOpenGroup()).isTrue();
        });
    }

    @Test
    void 품질_판본_변경은_전체_요청을_무효화하고_차량_요청은_요청_식별자로_구분한다() {
        // given
        DemandStatisticsRebuild whole = grouped();
        DemandStatisticsRebuild vehicle = DemandStatisticsRebuild.start(1, RebuildScope.vehicle("vehicle"),
            REQUEST, 1, UNTIL, 100, 200, 50);
        UUID renewed = UUID.fromString("00000000-0000-0000-0000-000000000224");

        // when
        final boolean wholeStillValid = whole.serves(REQUEST, 2);
        final boolean vehicleStillValid = vehicle.serves(REQUEST, 2);
        final boolean renewedVehicleValid = vehicle.serves(renewed, 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(wholeStillValid).isFalse();
            softly.assertThat(vehicleStillValid).isTrue();
            softly.assertThat(renewedVehicleValid).isFalse();
        });
    }

    @Test
    void 날짜_합산은_입력의_첫_등장_순서를_유지해_부동소수점_비트가_달라지지_않는다() {
        // given
        Clock clock = Clock.fixed(UNTIL, ZoneOffset.UTC);
        List<StopDemandHourlyTotals> originalOrder = List.of(
            new StopDemandHourlyTotals(1, UNTIL, 1.0, 0, 1, 1),
            new StopDemandHourlyTotals(1, UNTIL.plusSeconds(86400), 1e-16, 0, 1, 1),
            new StopDemandHourlyTotals(1, UNTIL.plusSeconds(172800), 1e-16, 0, 1, 1));
        List<StopDemandHourlyTotals> reversed = originalOrder.reversed();

        // when
        final long forwardBits = Double.doubleToLongBits(
            StopDemandAggregator.aggregate(originalOrder, clock).getFirst().cell().averageFillRate());
        final long reversedBits = Double.doubleToLongBits(
            StopDemandAggregator.aggregate(reversed, clock).getFirst().cell().averageFillRate());

        // then
        assertSoftly(softly -> {
            softly.assertThat(forwardBits).isEqualTo(Double.doubleToLongBits(1.0 / 3));
            softly.assertThat(reversedBits).isEqualTo(Double.doubleToLongBits((1.0 + 2e-16) / 3));
            softly.assertThat(forwardBits).isNotEqualTo(reversedBits);
        });
    }

    private static DemandStatisticsRebuild grouped() {
        return DemandStatisticsRebuild.start(1, RebuildScope.wholeRoute(), REQUEST, 1, UNTIL, 100, 200, 50);
    }
}
