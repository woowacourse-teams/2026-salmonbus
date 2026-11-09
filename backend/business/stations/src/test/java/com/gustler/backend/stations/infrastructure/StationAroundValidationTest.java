package com.gustler.backend.stations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisHttpResult;
import com.gustler.backend.gbis.api.GbisRawResponse;
import com.gustler.backend.stations.domain.StationAroundSource;
import com.gustler.backend.stations.infrastructure.gbis.GbisStationAroundSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class StationAroundValidationTest {
    @Test
    void 코드0의_깨진_본문은_정상_빈_결과로_취급하지_않는다() {
        // given
        GbisApiCaller caller = callerWith("{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":\"\"}}");
        GbisStationAroundSource source = new GbisStationAroundSource(caller, JsonMapper.builder().build());

        // when
        StationAroundSource.Result result = source.around(127.1, 37.4, "a");

        // then
        assertThat(result).isInstanceOf(StationAroundSource.Failed.class);
    }

    @Test
    void 코드4는_정상_빈_결과로_취급한다() {
        // given
        GbisApiCaller caller = callerWith("{\"response\":{\"msgHeader\":{\"resultCode\":4}}}");
        GbisStationAroundSource source = new GbisStationAroundSource(caller, JsonMapper.builder().build());

        // when
        StationAroundSource.Result result = source.around(127.1, 37.4, "a");

        // then
        assertThat(result).isInstanceOf(StationAroundSource.NoStations.class);
    }

    private static GbisApiCaller callerWith(String body) {
        GbisApiCaller caller = mock(GbisApiCaller.class);
        given(caller.getByCoordinateWithMetadata(anyString(), anyDouble(), anyDouble(), anyString()))
            .willReturn(new GbisHttpResult(new GbisRawResponse.Received(body), HttpStatus.OK.value(), null));
        return caller;
    }
}
