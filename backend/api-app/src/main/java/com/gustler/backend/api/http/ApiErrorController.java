package com.gustler.backend.api.http;

import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨테이너가 {@code /error} 로 전달한 응답도 서비스의 오류 응답 형식으로 반환한다.
 *
 * <p>모든 오류가 {@link ApiExceptionHandler} 를 지나는 것은 아니다. 톰캣이 {@code sendError}
 * 를 호출하면 {@code StandardHostValve} 가 그 요청을 컨텍스트의 {@code /error} 로 먼저 넘긴다.
 * 이 경로를 처리하지 않으면 스프링 부트 기본 컨트롤러가
 * {@code {"timestamp","status","error","path"}} 를 반환한다. 서비스 오류 코드와 {@code no-store}
 * 는 포함되지 않는다.
 *
 * <p>이 형식으로 반환되던 요청은 둘이다. {@code TRACE} 요청은 405, 쿠키를 250개 실은 요청은
 * 400 응답을 반환했다.
 *
 * <p>이 빈이 있으면 부트는 기본 {@code BasicErrorController} 를 생성하지 않는다.
 *
 * <p>{@code Content-Type} 을 명시하는 이유는 {@link ApiExceptionHandler} 와 같다.
 * 요청의 {@code Accept} 로 협상하게 두면 JSON 을 허용하지 않는 요청에서 본문이 사라진다.
 *
 * <h2>관리 포트는 이 형식을 사용하지 않는다</h2>
 *
 * <p>{@code management.server.port} 로 Actuator 를 별도 포트로 분리하면 그 포트는 자식 컨텍스트에서
 * 실행된다. 이 컨트롤러와 {@link RequestIdFilter} 와 {@link ContainerErrorResponseValve} 는 부모
 * 컨텍스트에만 등록되므로 관리 포트에는 적용되지 않는다. 관리 포트에서는 스프링 부트 기본 오류 응답이 반환된다.
 *
 * <p>의도한 구분이다. api 문서의 범위는 {@code /api/v1} 아래이며 Actuator 는 포함하지 않는다.
 * 노출 범위도 {@code health} 와 {@code info} 둘뿐이다. 해당 포트는 화면이 아닌 로드밸런서가 호출하므로
 * 서비스 오류 형식으로 바꾸면 부트 기본 응답을 읽는 도구가 처리하지 못할 수 있다.
 *
 * <p>공개 포트가 관리 포트 분리와 무관하게 이 형식을 지키는지는
 * {@code ManagementPortSeparationTest} 가 검증한다.
 */
@RestController
public class ApiErrorController implements ErrorController {
    /**
     * 서블릿 컨테이너가 전달한 오류도 기존 ErrorResponse 형식과 requestId 헤더로 통일한다.
     */
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ErrorResponse> handleContainerError(
        HttpServletRequest request
    ) {
        HttpStatus status = statusOf(request);
        ErrorCode code = ErrorCode.of(status);
        String requestId = RequestId.of(request);
        Object cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        ApiFailureLog.write(status.value(), code, requestId, attachedFailure(cause));

        return ResponseEntity.status(status)
            .headers(ErrorHeaders.of(MediaType.APPLICATION_JSON))
            .body(new ErrorResponse(code, code.message(), requestId));
    }

    /**
     * 컨테이너가 전달한 상태 코드다. 없거나 해석할 수 없으면 500 으로 본다.
     *
     * <p>오류 처리 경로이므로 200 으로 되돌리지 않는다.
     */
    private HttpStatus statusOf(
        HttpServletRequest request
    ) {
        Object attached = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (!(attached instanceof Integer value)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        HttpStatus resolved = HttpStatus.resolve(value);

        if (resolved == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return resolved;
    }

    private static Throwable attachedFailure(Object cause) {
        if (cause instanceof Throwable failure) {
            return failure;
        }
        return null;
    }
}
