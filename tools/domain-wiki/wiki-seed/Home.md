# Salmonbus AI 정책 Wiki

이 Wiki는 AI 정책 질의와 PR 정책 검증에 사용하는 현재 정책 저장소다.

- 정책 목록: [Policy Index](Policy-Index)
- 규칙 상태와 근거: [Policy Registry](Policy-Registry.json)
- 코드 경로 연결: [Code Policy Map](Code-Policy-Map)

## 근거의 역할

- Git Wiki: 현재 적용할 규칙, 조건, 예외
- Confluence 회의록: 결정 원문과 논의 문맥(읽기 전용)
- 코드와 테스트: 특정 Git SHA에서 확인한 구현 사실

`Policy-Registry.json`에서 `active`이고 승인 정보가 있는 규칙만 정책 위반 판정에 사용한다.
초안, 회의록, 현재 코드 동작만으로 정책을 자동 확정하지 않는다.

## AI 검수 순서

1. 실행 시점의 Wiki 커밋 SHA를 고정한다.
2. PR 본문과 변경 코드를 읽고 관련 정책을 찾는다.
3. 저렴한 모델(`gpt-6-luna`)이 후보를 찾는다.
4. 검수 모델(`gpt-6.1-sol`)이 반대 근거를 확인한다.
5. 저렴한 모델이 답변하거나 후보를 철회한다.
6. 검수 모델이 다시 확인한다.
7. 80점 이상이면서 모든 필수 조건을 통과한 문제만 PR 댓글로 남긴다.
8. `dev` 머지 뒤 같은 2회 검수를 거친 문서 변경만 Git Wiki에 반영한다.
