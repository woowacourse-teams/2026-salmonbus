# 앱 화면 캡처 출처

이 폴더의 PNG 18장은 시안을 다시 그린 이미지가 아니라, `fe/feat/SAL-249`의 커밋 `05d3f607cb97c7794bc819e65924e2a8730e37ea`를 로컬에서 실행하고 Chromium으로 촬영한 실제 React 화면입니다. 원본 PNG는 각각 **1275 × 2532px**이며, 425 × 844 CSS 픽셀 화면을 **DPR 3**으로 직접 렌더링했습니다. 저해상도 캡처를 확대하지 않았습니다.

## 촬영 환경

- 커밋된 소스만 `git archive`로 `/private/tmp/sal249-review-capture`에 분리했습니다. 기존 작업 폴더의 미커밋 변경은 포함하지 않았습니다.
- 독립 개발 서버 `http://127.0.0.1:4211`, Chromium, `ko-KR`, `Asia/Seoul` 환경입니다.
- `document.fonts.ready`와 이미지 decode를 기다렸으며, `document.fonts.status === "loaded"`를 확인했습니다. 각 화면에서 제어된 브라우저 시계를 700ms 진행한 뒤 유한 애니메이션을 완료한 상태로 촬영했습니다.
- `page.screenshot({ scale: "device", animations: "disabled" })`를 사용했습니다. 화면이나 CSS를 캡처용으로 새로 그리지 않았습니다.
- 촬영 시각·URL·원본 크기·SHA-256은 [manifest.json](manifest.json)에 기록했습니다. 브라우저의 표시 시각은 API fixture의 고정된 예보 시각이며 실제 촬영 시각과 다릅니다.

## 모의 데이터와 확인 범위

- 로그인·즐겨찾기·판정보드·도착정보 응답은 저장소의 `e2e/fixtures/`를 재사용했습니다. 닉네임 `살몽`, 그룹 `출퇴근`, `통학`, `주말 나들이`와 정류장명은 촬영용 모의 데이터입니다.
- 그룹 생성·즐겨찾기 저장·다중 삭제 요청도 모의 API 안에서만 처리했습니다. 실제 회원 정보나 즐겨찾기를 쓰거나 삭제하지 않았습니다.
- 지도는 `e2e/fixtures/naver-map.ts`의 **지도 SDK 테스트 대역**입니다. 단순한 격자·노선 배경은 대역의 표시이며 실제 네이버 지도 지형·라벨 품질을 나타내지 않습니다. 판정보드 캡처는 일반 노선 진입 후 지도 초기 배율 계산을 기다리고 정류장을 실제 클릭해 촬영했습니다.
- 화면 04는 ETA 있음/없음, 좌석 예보 높음/보통/낮음을 함께 표시합니다. 화면 05는 `NO_VEHICLE`과 `ERROR` 상태를 모의합니다.
- 화면 11은 새 그룹의 이름 입력 단계입니다. 촬영 뒤 `만들기` → 새 그룹 자동 선택 → `저장` → `변경사항을 저장했습니다` 토스트까지 추가로 확인했습니다.
- 화면 16~18은 대표 그룹을 제외한 2개 그룹 선택 → 삭제 확인 → 순차 삭제 완료를 모의합니다. 화면 15는 첫 번째 대표 그룹의 삭제 보호 화면입니다.
- PNG는 정지 화면 자료입니다. 별 이동, 그룹 손잡이 등장, 드래그 정렬, 줌인, 하단 탭 전환의 시간에 따른 움직임은 별도 E2E 테스트나 실제 실행으로 확인해야 합니다.

## 화면 목록

| 파일                                                                             | 내용                                                               |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| [01-guest-home.png](01-guest-home.png)                                           | 비회원 홈: 로그인 안내와 브랜드 홈 링크                            |
| [02-guest-mypage.png](02-guest-mypage.png)                                       | 비회원 마이페이지: 살몽이 로고와 로그인 안내                       |
| [03-guest-board-login.png](03-guest-board-login.png)                             | 비회원 판정보드: 즐겨찾기 로그인 안내 팝업                         |
| [04-home-favorites.png](04-home-favorites.png)                                   | 홈: 즐겨찾기 판정보드와 도착 시간·정류장 수·좌석 예보              |
| [05-home-favorite-states.png](05-home-favorite-states.png)                       | 홈: 도착 예정 차량 없음과 개별 즐겨찾기 조회 오류                  |
| [06-home-inline-editor.png](06-home-inline-editor.png)                           | 홈: 모달 없이 전환된 편집 영역, 그룹·정류장 손잡이와 휴지통        |
| [07-home-empty-editor.png](07-home-empty-editor.png)                             | 홈 편집: 빈 그룹의 0 / 2 안내                                      |
| [08-favorite-delete-dialog.png](08-favorite-delete-dialog.png)                   | 홈 편집: 선택 그룹의 즐겨찾기 삭제 확인                            |
| [09-board-expanded-favorite.png](09-board-expanded-favorite.png)                 | 판정보드: 펼친 정류장 이름 옆 즐겨찾기 별과 정류장 예보            |
| [10-board-group-selection.png](10-board-group-selection.png)                     | 판정보드 저장할 그룹 선택: 정류장명·방면을 제거하고 그룹 추가 제공 |
| [11-board-create-group.png](11-board-create-group.png)                           | 판정보드 모달 안의 새 그룹 이름 입력 화면                          |
| [12-mypage-groups.png](12-mypage-groups.png)                                     | 마이페이지: 대표 그룹 표시, 그룹 삭제와 세로 슬라이더 수정 버튼    |
| [13-mypage-group-details.png](13-mypage-group-details.png)                       | 마이페이지 그룹 상세: 대표 배지와 정류장 개별 삭제                 |
| [14-mypage-rename-group.png](14-mypage-rename-group.png)                         | 그룹 이름 변경: 제목·라벨·입력·정책·저장 순서                      |
| [15-representative-group-protection.png](15-representative-group-protection.png) | 대표 그룹 삭제 보호: 순서상 첫 번째 그룹은 삭제할 수 없음          |
| [16-mypage-bulk-selection.png](16-mypage-bulk-selection.png)                     | 그룹 다중 삭제: 대표 그룹 제외 체크박스 선택과 취소                |
| [17-mypage-bulk-confirm.png](17-mypage-bulk-confirm.png)                         | 선택한 그룹 2개 삭제 확인 모달                                     |
| [18-mypage-bulk-complete.png](18-mypage-bulk-complete.png)                       | 다중 삭제 완료: 대표 그룹 유지와 완료 토스트                       |

## 캡처 검증과 재현

캡처 전용 Playwright 시나리오 3개가 모두 통과했습니다. 각 파일의 PNG 서명·크기(1275 × 2532)·파일 수(18개)도 확인했고, 18개 전체를 화면 모음으로 검수했습니다. 자동 테스트와 캡처 결과는 이 커밋의 로컬 Chromium 화면을 보여 주며 실제 API 배포 상태, 실기기 및 네이티브 지도 검증을 대신하지 않습니다.

실행에 사용한 임시 파일은 `/private/tmp/sal249-review-capture.spec.ts`, `/private/tmp/sal249-review-capture.config.ts`입니다. `git archive`로 만든 소스의 `frontend/e2e/review-capture.spec.ts`와 `frontend/review-capture.config.ts`에 복사한 뒤 다음 명령을 실행했습니다.

```sh
env WEBPACK_AMPLITUDE_API_KEY= APP_VERSION=sal249-review node node_modules/webpack-cli/bin/cli.js serve --config webpack/webpack.dev.cjs --host 127.0.0.1 --port 4211
node_modules/.bin/playwright test --config review-capture.config.ts
```

원본 테스트·캡처 실행 로그는 `/private/tmp/sal249-capture-run.log`, 서버 로그는 `/private/tmp/sal249-capture-dev.log`에 있습니다. 위 임시 파일은 이 작업 세션의 재현 자료이며 저장소 코드나 배포에 포함되지 않습니다.
