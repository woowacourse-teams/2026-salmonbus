package com.gustler.backend.gbis.api;

import com.gustler.backend.gbis.api.GbisRawResponse.NotReceived;
import com.gustler.backend.gbis.api.GbisRawResponse.PortalRejected;
import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import com.gustler.backend.gbis.api.GbisRouteResult.RouteInfoUnavailable;
import com.gustler.backend.gbis.api.GbisRouteResult.StationsUnavailable;
import com.gustler.backend.gbis.api.GbisRouteResult.Success;
import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse;
import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse.RouteInfoItem;
import com.gustler.backend.gbis.api.dto.BusRouteStationResponse;
import com.gustler.backend.gbis.api.dto.BusRouteStationResponse.RouteStationItem;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 노선 하나의 기본 정보와 경유 정류소를 상류에서 읽는다.
 *
 * <p>상류가 둘을 따로 준다. 표시명 · 기점 · 종점 · 첫차 · 막차는 노선정보에 있고, 정류소 목록과
 * <b>회차 순번</b>은 노선정류소 조회에 있다. 그래서 한 노선을 읽는 데 호출을 두 번 쓴다.
 *
 * <p>여기까지가 통신과 응답 해석이다. 읽은 값을 업무 의미로 바꾸는 일은 부른 쪽 어댑터가 맡는다.
 */
@Component
public class GbisRouteInfoSource {
    /** 한 노선을 읽는 데 드는 상류 호출 수. 장부에서 이 호출 수를 예약한 뒤 호출한다. */
    public static final int UPSTREAM_CALLS_PER_READ = 2;

    private static final String ROUTE_INFO_PATH = "/busrouteservice/v2/getBusRouteInfoItemv2";
    private static final String ROUTE_STATION_PATH = "/busrouteservice/v2/getBusRouteStationListv2";

    private final GbisApiCaller caller;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    public GbisRouteInfoSource(
        GbisApiCaller caller,
        ObjectMapper objectMapper
    ) {
        this(caller,objectMapper,java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GbisRouteInfoSource(GbisApiCaller caller,ObjectMapper objectMapper,java.util.Optional<java.time.Clock> clock) {
        this.caller=caller;this.objectMapper=objectMapper;this.clock=clock.orElseGet(java.time.Clock::systemUTC);
    }

    public enum CaptureStatus { SUCCESS, HTTP_ERROR, TRANSPORT_FAILURE, INVALID_RESPONSE,
        SENSITIVE_RESPONSE, UNRECORDED_LEGACY }
    public record ResponseCapture(String operation,int httpStatus,java.time.Instant acquiredAt,
                                  CaptureStatus status,byte[] body) {
        public ResponseCapture {
            body=body==null?null:body.clone();
            if(status!=CaptureStatus.SUCCESS && body!=null)
                throw new IllegalArgumentException("Failed catalog capture cannot retain body");
        }
        @Override public byte[] body() { return body==null?null:body.clone(); }
        @Override public String toString() { return "CatalogResponseCapture[operation="+operation+", httpStatus="+httpStatus+", status="+status+"]"; }
    }
    public record Capture(GbisRouteResult result,List<ResponseCapture> responses) {
        public Capture {
            responses=List.copyOf(responses);
            if(result instanceof Success success) result=new Success(success.routeInfo(),List.copyOf(success.stations()));
        }
        @Override public String toString() { return "CatalogCapture[resultType="+result.getClass().getSimpleName()+", responses="+responses+"]"; }
    }

    /** Original responses are retained only after endpoint acceptance; sensitive payloads never reach normalization. */
    public Capture capture(String routeId) {
        var receipts=new java.util.ArrayList<ResponseCapture>();
        var infoHttp=caller.getWithMetadata(ROUTE_INFO_PATH,routeId,GbisKey.PRIMARY);
        var infoAt=clock.instant();
        BusRouteInfoResponse info=parseCaptured(infoHttp,BusRouteInfoResponse.class,routeId);
        boolean infoValid=isSuccessful(info) && info.response().body().routeInfo()!=null
            && info.response().body().routeInfo().displayName()!=null
            && info.response().body().routeInfo().startStopName()!=null
            && info.response().body().routeInfo().endStopName()!=null;
        var infoReceipt=receipt("ROUTE_INFO",infoHttp,infoAt,infoValid);
        receipts.add(infoReceipt);
        if(!infoValid || infoReceipt.status()==CaptureStatus.SENSITIVE_RESPONSE)
            return new Capture(new RouteInfoUnavailable(),receipts);
        var stopsHttp=caller.getWithMetadata(ROUTE_STATION_PATH,routeId,GbisKey.PRIMARY);
        var stopsAt=clock.instant();
        BusRouteStationResponse stops=parseCaptured(stopsHttp,BusRouteStationResponse.class,routeId);
        boolean stopsValid=isSuccessful(stops) && stops.response().body().stations()!=null
            && !stops.response().body().stations().isEmpty();
        var stopsReceipt=receipt("ROUTE_STATIONS",stopsHttp,stopsAt,stopsValid);
        receipts.add(stopsReceipt);
        if(!stopsValid || stopsReceipt.status()==CaptureStatus.SENSITIVE_RESPONSE)
            return new Capture(new StationsUnavailable(),receipts);
        return new Capture(new Success(info.response().body().routeInfo(),stops.response().body().stations()),receipts);
    }
    private <T> T parseCaptured(GbisHttpResult http,Class<T> type,String routeId) {
        if(http.httpStatus()!=200 || http.capture().disposition()==GbisResponseCapture.Disposition.SENSITIVE_RESPONSE
                || !(http.response() instanceof Received received)) return null;
        try {
            var root=objectMapper.readTree(received.body()).path("response");
            var code=root.path("msgHeader").path("resultCode");
            if(!"0".equals(code.asString(null))) return null;
            var rows=root.path("msgBody").path(type==BusRouteInfoResponse.class?"busRouteInfoItem":"busRouteStationList");
            if(rows.isArray()) {
                for(var row:rows) if(row.has("routeId") && !routeId.equals(row.path("routeId").asString(null))) return null;
            } else if(rows.has("routeId") && !routeId.equals(rows.path("routeId").asString(null))) return null;
            return parse(received.body(),type);
        } catch(RuntimeException invalid) { return null; }
    }
    private static ResponseCapture receipt(String operation,GbisHttpResult http,java.time.Instant at,boolean valid) {
        CaptureStatus status;
        if(http.capture().disposition()==GbisResponseCapture.Disposition.SENSITIVE_RESPONSE) status=CaptureStatus.SENSITIVE_RESPONSE;
        else if(http.httpStatus()==0) status=CaptureStatus.TRANSPORT_FAILURE;
        else if(http.httpStatus()!=200) status=CaptureStatus.HTTP_ERROR;
        else if(!valid) status=CaptureStatus.INVALID_RESPONSE;
        else status=http.capture().disposition()==GbisResponseCapture.Disposition.AVAILABLE
            ? CaptureStatus.SUCCESS : CaptureStatus.UNRECORDED_LEGACY;
        return new ResponseCapture(operation,http.httpStatus(),at,status,status==CaptureStatus.SUCCESS?http.capture().body():null);
    }

    /**
     * GBIS 노선 정보를 호출해 원천 필드를 반환한다. 알 수 없는 값과 조회 실패를 임의의 정상 기본값으로 채우지 않는다.
     */
    public GbisRouteResult read(
        String routeId
    ) {
        return capture(routeId).result();
    }

    private boolean isSuccessful(BusRouteInfoResponse response) {
        if (response == null || response.response() == null) {
            return false;
        }
        BusRouteInfoResponse.Response providerResponse = response.response();
        if (providerResponse.header() == null || providerResponse.body() == null) {
            return false;
        }
        BusRouteInfoResponse.Header header = providerResponse.header();
        return GbisResultCode.from(header.resultCode()) == GbisResultCode.SUCCESS;
    }

    private boolean isSuccessful(BusRouteStationResponse response) {
        if (response == null || response.response() == null) {
            return false;
        }
        BusRouteStationResponse.Response providerResponse = response.response();
        if (providerResponse.header() == null || providerResponse.body() == null) {
            return false;
        }
        BusRouteStationResponse.Header header = providerResponse.header();
        return GbisResultCode.from(header.resultCode()) == GbisResultCode.SUCCESS;
    }

    private <T> T parse(
        String body,
        Class<T> responseType
    ) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JacksonException e) {
            return null;
        }
    }
}
