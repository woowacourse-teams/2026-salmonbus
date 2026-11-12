package com.gustler.backend.api.route.application;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.util.Optional;

/**
 * {@link RouteGeometryQueryService#respond}가 한 트랜잭션 안에서 부르는 응답 생성자다. 원본을 읽어 지문을 만든 뒤
 * {@link #reuse}로 이미 만든 응답을 찾고, 없으면 같은 원본으로 조립한 형상을 {@link #create}에 넘긴다.
 *
 * @param <T> 응답
 */
public interface RouteGeometryResponder<T> {

    /**
     * {@code fingerprint}로 정해지는 응답을 이미 만들었으면 준다. 없으면 빈 값이다.
     */
    Optional<T> reuse(RouteGeometryFingerprint fingerprint);

    /**
     * 조립한 형상으로 응답을 만든다. {@code fingerprint}가 null이면 이 응답을 다른 요청에 다시 쓰면 안 된다.
     */
    T create(RouteGeometry geometry, RouteGeometryFingerprint fingerprint);
}
