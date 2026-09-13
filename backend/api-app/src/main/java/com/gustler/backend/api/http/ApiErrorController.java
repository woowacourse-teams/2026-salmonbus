package com.gustler.backend.api.http;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨테이너가 {@code /error} 로 넘긴 응답도 우리 오류 응답 형식으로 낸다.
 *
 * <p>모든 오류가 {@link ApiExceptionHandler} 를 지나는 것은 아니다. 톰캣이 {@code sendError}
 * 를 부르면 {@code StandardHostValve} 가 그 요청을 컨텍스트의 {@code /error} 로 먼저 넘긴다.
 * 그 자리를 안 잡으면 스프링 부트 기본 컨트롤러가
 * {@code {"timestamp","status","error","path"}} 를 내보낸다. 우리 오류 코드도 {@code no-store}
 * 도 없는 응답이다.
 *
 * <p>실제로 그렇게 새던 요청 둘이다. {@code TRACE} 로 부르면 405, 쿠키를 250개 실어 보내면
 * 400 이 그 모양으로 나갔다.
 *
 * <p>이 빈이 있으면 부트가 자기 {@code BasicErrorController} 를 안 만든다.
 *
 * <p>{@code Content-Type} 을 직접 박는 이유는 {@link ApiExceptionHandler} 와 같다.
 * 요청의 {@code Accept} 로 협상하게 두면 JSON 을 안 받는 요청에서 본문이 사라진다.
 *
 * <h2>관리 포트는 이 형식을 안 쓴다</h2>
 *
 * <p>{@code management.server.port} 로 Actuator 를 다른 포트에 떼면 그 포트는 자식 컨텍스트에서
 * 돈다. 이 컨트롤러와 {@link RequestIdFilter} 와 {@link ContainerErrorResponseValve} 는 부모
 * 컨텍스트에만 붙어서 <b>관리 포트에는 안 걸린다.</b> 거기서는 스프링 부트 기본 오류 응답이 나간다.
 *
 * <p>일부러 그렇게 뒀다. api 문서가 정한 것은 {@code /api/v1} 아래이고 Actuator 는 안 적혀 있다.
 * 노출도 {@code health} 와 {@code info} 둘뿐이다. 그 포트를 부르는 것은 화면이 아니라 로드밸런서라,
 * 우리 형식으로 바꾸면 부트 기본 모양을 읽는 도구가 오히려 못 읽을 수 있다.
 *
 * <p>공개 포트가 관리 포트 분리와 무관하게 이 형식을 지키는지는
 * {@code ManagementPortSeparationTest} 가 붙잡는다.
 */
@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ErrorResponse> handleContainerError(
        HttpServletRequest request
    ) {
        HttpStatus status = statusOf(request);
        ErrorCode code = ErrorCode.of(status);
        String requestId = RequestId.of(request);
        log.warn("컨테이너가 오류로 넘긴 요청이다. 상태={} 오류코드={} requestId={}",
            status.value(), code.name(), requestId);

        return ResponseEntity.status(status)
            .headers(ErrorHeaders.of(MediaType.APPLICATION_JSON))
            .body(new ErrorResponse(code, code.message(), requestId));
    }

    /**
     * 컨테이너가 실어 준 상태 코드. 없거나 해석할 수 없으면 500 으로 본다.
     *
     * <p>여기 오는 것 자체가 무언가 잘못됐다는 뜻이라 200 으로 되돌리지 않는다.
     */
    private HttpStatus statusOf(
        HttpServletRequest request
    ) {
        Object attached = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (!(attached instanceof Integer value)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        HttpStatus resolved = HttpStatus.resolve(value);

        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }
}
