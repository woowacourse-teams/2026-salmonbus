# SAL-249 PR #153 Excalidraw 구조 자료

## 결과

앱 코드·의존성은 변경하지 않았습니다. 다음 6개 아티팩트만 `frontend/docs/favorites-review-20261023/diagrams/` 아래에 생성했습니다.

| 내용                          | 원본                                 | 벡터 출력                     | 2× PNG                                    |
| ----------------------------- | ------------------------------------ | ----------------------------- | ----------------------------------------- |
| 전체 연결 구조                | favorites-architecture.excalidraw    | favorites-architecture.svg    | favorites-architecture.png (2992×3972)    |
| 순서 저장·즉시 삭제·실패 복구 | favorites-save-and-delete.excalidraw | favorites-save-and-delete.svg | favorites-save-and-delete.png (2992×4152) |

PR/Confluence에는 PNG 미리보기를 넣고, 편집하려는 사람을 위해 .excalidraw 원본 링크를 같이 넣으면 좋습니다. SVG는 글자를 확대하기 좋습니다.

## 생성·검증

- 기존 `/private/tmp/sal249-pr-visuals/tools/node_modules/@excalidraw/excalidraw` 0.18을 재사용했습니다. 앱 package.json·lockfile에 추가하지 않았습니다.
- 임시 생성 스크립트 `/private/tmp/sal249-review-diagrams/render-entry.js`의 skeleton을 공식 `convertToExcalidrawElements`로 변환했습니다.
- 네이티브 scene JSON은 `type=excalidraw, version=2, elements, appState, files` 형식이며, 공식 `restore(JSON.parse(JSON.stringify(scene)))`로 재입력한 뒤 element 개수 일치도 확인했습니다.
- 공식 `exportToSvg`, `exportToCanvas(getDimensions scale=2)`로 실제 Chromium에서 출력했습니다. 수작업 SVG 도형으로 원본을 흉내 낸 자료가 아닙니다.
- 최종 구조도 67요소/44텍스트, 저장·삭제도 71요소/43텍스트입니다.
- 텍스트의 scene/card 경계 넘침을 자동 검사하고 두 PNG를 `view_image`로 확인했습니다. 제목·한글·카드·문구·화살표 겹침이 없습니다. 구조도의 주요 층 연결 화살표는 App→화면, 홈→FavoriteEditor, 편집기→FavoritesProvider, 조회 hook→API, 공통 요청→서버, 서버→schema, 정류장 링크→지도 접점입니다.
- render 로그의 유일한 404는 브라우저 favicon 요청이며, 폰트·모듈·이미지 렌더링 실패가 아닙니다.

## 기준·출처

반드시 PR 실제 base `74147a437697cf3b228c8c73b2ab33196cc2bc50` → head `05d3f607cb97c7794bc819e65924e2a8730e37ea`를 기준으로 했습니다. 로컬 dev나 미커밋 변경은 포함하지 않았습니다.

`git show 05d3f607:frontend/...`로 확인한 주요 파일:

- `src/app/App.tsx`, `routes.tsx`, `providers/FavoritesProvider.tsx`, 기존 `AuthProvider.tsx`, `AuthReturn.tsx`.
- `src/shared/hooks/useFavorites.ts`, `useFavoriteAction.ts`.
- `src/shared/api/favorites.api.ts`, `favorites.schema.ts`, `favorites.types.ts`, 기존 `auth.api.ts`, `client.ts`, `usePolledRequest.ts`.
- `src/pages/route-select/components/HomeFavorites.tsx`.
- `src/pages/verdict-board/components/FavoriteButton.tsx`.
- `src/pages/my-page/components/GroupList.tsx`.
- `src/pages/favorite-group/components/FavoriteGroupScreen.tsx`, `GroupDetails.tsx`.
- `src/shared/components/favorite-editor/FavoriteEditor.tsx`, `SortableList.tsx`.
- `src/pages/verdict-board/map/RouteMap.tsx`, `nativeMapAdapter.ts`, `naverMapAdapter.ts`.

## 도표에 명시한 핵심 정책

- 회색: 기존 인증/공통 요청/지도 접점 재사용. 연어색: 이번 PR의 즐겨찾기 주요 코드. 인증·지도 전체를 새로 만든 것처럼 표시하지 않습니다.
- 그룹/항목 조회와 예보 조회는 다른 API이며 선택 그룹 또는 전체 조회를 구분합니다.
- 홈·마이페이지는 동일 `FavoriteEditor`를 사용하고, 그룹 선택 모달은 판정보드 전용입니다.
- 순서는 완료할 때만 저장하며 그룹 순서 → 그룹별 항목 순서 순으로 요청합니다. 각 성공 직후 saved 갱신, 실패 시 다음 요청 중단, 재시도 시 남은 차이만 전송합니다.
- 항목 삭제는 확인 즉시 해당 그룹에 반영하고 draft/saved 양쪽 ID를 지웁니다.
- 그룹 다중 삭제도 확인 즉시 단건 DELETE를 순차 실행합니다. 첫 실패 중단 후 목록 재조회, 성공 ID를 선택에서 제거, 남은 대상만 재시도합니다.
- 대표는 저장된 그룹 순서의 첫 번째이며 순서를 저장하면 대표도 바뀝니다. 대표 보호는 현재 FE에서만 보장하며 서버 보강은 별도입니다.
- 취소는 미저장 순서/삭제 전 선택만 버립니다. 이미 성공한 삭제나 저장은 되돌리지 않습니다. 판정보드 모달에서 생성한 그룹도 취소 후 남습니다.

## 제한

- 이 작업은 문서 시각 자료 검증입니다. 실제 회원 정보나 API 삭제를 실행하지 않았고 iOS 실기기를 테스트하지 않았습니다.
- SVG는 시스템 한글 글꼴에 의존할 수 있어 PR 미리보기는 한글이 래스터화된 고화질 PNG를 권장합니다. `.excalidraw`는 편집 가능한 원본입니다.
- 전 파일 의존성·기존 채팅 시스템을 나열한 그래프가 아니라, 이 PR을 리뷰하는 데 필요한 주요 개인화 구조/공통 기반/지도 접점을 보여주는 구조도입니다.

최종 문구 감사 반영: 다중 삭제에서 재선택을 요구하는 조건은 “선택한 그룹이 사라지거나 대표가 되면”으로 좁혔습니다. 그룹 이름이나 무관한 항목 변경까지 막는다는 뜻이 아닙니다. scene/SVG/PNG를 모두 재생성하고 텍스트 경계·restore 검사 및 PNG 시각 QA를 다시 통과했습니다.
