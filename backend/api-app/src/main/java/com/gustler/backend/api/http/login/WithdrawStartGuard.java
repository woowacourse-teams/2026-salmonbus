package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.token.WithdrawalTickets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 애플 탈퇴 시작({@code GET /api/auth/apple/start?intent=withdraw&ticket=...})은 유효한 탈퇴 티켓이 있을 때만 한다.
 *
 * <p>티켓은 CSRF 로 보호되는 {@code POST /api/me/withdrawal/apple} 에서만 받을 수 있다. 인증 쿠키만으로 시작을 허락하면
 * 다른 사이트가 링크 하나로 탈퇴 흐름을 열 수 있다. 티켓이 유효하면 회원 ID 를 요청 속성에 두고, 시작 처리가 그것을 임시
 * 세션에 옮긴다. 티켓이 없거나 만료됐거나 위조됐으면 세션을 만들지 않고 돌아갈 경로에 {@code withdraw=failed} 를 붙여
 * 돌려보낸다. 브라우저 이동이라 JSON 오류를 보여 주면 사용자가 앱으로 돌아갈 길이 없다.
 */
final class WithdrawStartGuard extends OncePerRequestFilter {
    static final String MEMBER_ATTRIBUTE = WithdrawStartGuard.class.getName() + ".MEMBER";

    private static final String APPLE_START = "/api/auth/apple/start";
    private static final String WITHDRAW_INTENT = "withdraw";

    private final WithdrawalTickets tickets;

    WithdrawStartGuard(WithdrawalTickets tickets) {
        this.tickets = tickets;
    }

    static boolean isWithdrawStart(HttpServletRequest request) {
        return APPLE_START.equals(request.getRequestURI()) && WITHDRAW_INTENT.equals(request.getParameter("intent"));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isWithdrawStart(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<UUID> member = tickets.verify(request.getParameter("ticket"));
        if (member.isEmpty()) {
            String location = UriComponentsBuilder.fromUriString(ReturnTo.sanitize(request.getParameter("returnTo")))
                .replaceQueryParam("withdraw", "failed")
                .build()
                .toString();
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, location);
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            return;
        }
        request.setAttribute(MEMBER_ATTRIBUTE, member.get());
        filterChain.doFilter(request, response);
    }
}
