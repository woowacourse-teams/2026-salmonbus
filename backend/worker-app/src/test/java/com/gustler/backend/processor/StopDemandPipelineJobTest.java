package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class StopDemandPipelineJobTest {
    @Test
    void 한_호출에_한_노선만_처리하고_다음_호출은_다른_노선으로_넘긴다() {
        var pipeline=mock(StopDemandPipeline.class);
        var routes=mock(RouteVersionRepository.class);
        var now=Instant.parse("2026-09-25T00:00:00Z");
        var job=new StopDemandPipelineJob(pipeline,routes,Clock.fixed(now,ZoneId.of("Asia/Seoul")));
        when(routes.findActiveVersionIds()).thenReturn(List.of(2L,1L));
        when(pipeline.step(anyLong())).thenReturn(new StopDemandPipeline.Step("PROGRESSED","FOLD",now));

        job.advance();
        verify(pipeline).step(1L);
        verifyNoMoreInteractions(pipeline);
        job.advance();
        verify(pipeline).step(2L);
    }

    @Test
    void 실패한_노선은_즉시_재시도하지_않고_다른_노선을_처리한다() {
        var pipeline=mock(StopDemandPipeline.class);
        var routes=mock(RouteVersionRepository.class);
        var now=Instant.parse("2026-09-25T00:00:00Z");
        var job=new StopDemandPipelineJob(pipeline,routes,Clock.fixed(now,ZoneId.of("Asia/Seoul")));
        when(routes.findActiveVersionIds()).thenReturn(List.of(1L,2L));
        var failure=new IllegalStateException("synthetic failure");
        when(pipeline.step(1L)).thenThrow(failure);
        when(pipeline.step(2L)).thenReturn(new StopDemandPipeline.Step("PROGRESSED","FOLD",now));

        assertThatThrownBy(job::advance).isSameAs(failure);
        job.advance();
        job.advance();

        verify(pipeline,times(1)).step(1L);
        verify(pipeline,times(2)).step(2L);
    }
}
