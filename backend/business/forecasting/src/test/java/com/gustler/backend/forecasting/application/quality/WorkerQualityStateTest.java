package com.gustler.backend.forecasting.application.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerQualityStateTest {
    @Test
    void 품질이_바뀌지_않으면_DB에서_읽은_버전과_조사_차량_목록을_재사용한다() {
        // given
        var calls = new AtomicInteger();
        var state = new WorkerQualityState(version -> {
            calls.incrementAndGet();
            return snapshot();
        }, 2);

        // when
        var first = state.read(1).orElseThrow();
        var second = state.read(1).orElseThrow();

        // then
        assertThat(second.snapshot()).isEqualTo(first.snapshot());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void DB를_읽는_동안_품질_변경이_시작되면_조회_결과를_메모리에_넣지_않는다() {
        // given
        var reference = new AtomicReference<WorkerQualityState>();
        var calls = new AtomicInteger();
        var state = new WorkerQualityState(version -> {
            if (calls.incrementAndGet() == 1) reference.get().beginChange().close();
            return snapshot();
        }, 2);
        reference.set(state);

        // when
        var interrupted = state.read(1);
        var next = state.read(1);

        // then
        assertThat(interrupted).isEmpty();
        assertThat(next).isPresent();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void DB_조회가_실패하면_이전_품질_정보를_대신_반환하지_않는다() {
        // given
        var calls = new AtomicInteger();
        var state = new WorkerQualityState(version -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("조회 실패");
            return snapshot();
        }, 2);
        state.read(1);
        state.beginChange().close();

        // when

        // then
        assertThatThrownBy(() -> state.read(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.read(1)).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void 메모리에서_제거된_노선의_품질_정보는_DB에서_다시_읽는다() {
        // given
        var calls = new AtomicInteger();
        var state = new WorkerQualityState(version -> {
            calls.incrementAndGet();
            return snapshot();
        }, 1);

        // when
        state.read(1);
        state.read(2);
        state.read(1);

        // then
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void 하나의_품질_변경이_끝나도_다른_변경이_남아_있으면_메모리를_사용하지_않는다() {
        // given
        var state = new WorkerQualityState(version -> snapshot(), 2);
        var first = state.beginChange();
        var second = state.beginChange();

        // when
        first.close();
        first.close();

        // then
        assertThat(state.read(1)).isEmpty();
        second.close();
        assertThat(state.read(1)).isPresent();
    }

    private static WorkerQualityState.Snapshot snapshot() {
        return new WorkerQualityState.Snapshot(7, Set.of("vehicle-A"));
    }
}
