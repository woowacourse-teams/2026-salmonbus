package com.gustler.backend.api.docs;

import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;

/** 공개 문서의 API 묶음 이름과 표시 순서다. */
public final class ApiDocsTags {
    public static final String ROUTES = "노선";
    public static final String ARRIVALS = "정류장 도착정보";
    public static final String AUTH = "로그인";
    public static final String MEMBER = "회원";
    public static final String FAVORITES = "즐겨찾기";

    private ApiDocsTags() {
    }

    /** 문서에 보일 순서대로 묶음과 설명을 돌려준다. 호출마다 새 객체를 만든다. */
    public static List<Tag> ordered() {
        return List.of(
            new Tag().name(ROUTES).description("노선 목록, 판정 보드, 차량 위치, 지도 형상"),
            new Tag().name(ARRIVALS).description("정류장별 도착 예정 시각과 현재 잔여 좌석"),
            new Tag().name(AUTH).description("로그인 상태, 토큰 갱신, 로그아웃. 로그인이 켜져 있을 때만 표시"),
            new Tag().name(MEMBER).description("내 정보, 닉네임 변경, 탈퇴. 로그인이 켜져 있을 때만 표시"),
            new Tag().name(FAVORITES).description("즐겨찾기 그룹 관리와 접근 차량 조회. 로그인이 켜져 있을 때만 표시")
        );
    }
}
