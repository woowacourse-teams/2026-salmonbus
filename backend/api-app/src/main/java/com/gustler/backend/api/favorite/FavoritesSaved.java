package com.gustler.backend.api.favorite;

/**
 * 회원의 즐겨찾기를 저장한 트랜잭션 안에서 발행한다. 듣는 쪽은 같은 트랜잭션에서
 * 즐겨찾기에 기대는 데이터를 맞춘다. 커밋 뒤로 미루면 저장과 정리 사이에 어긋난 상태가 보인다.
 */
public record FavoritesSaved(long memberId) {
}
