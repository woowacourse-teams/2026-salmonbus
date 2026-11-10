# 연어행동 구현 기록 (2026-11-08)

## 범위와 지위

- 규범은 SALMON-MATH-v1.md(성공은 원하는 방향 버스에 실제 탑승)이고 구현 기준은 SALMON-ACTION-IMPLEMENTATION-DESIGN-v1.md이다.
- 설계 11.1의 선행 조건인 권고와 제안 해소(Q1, 4.1, 4.2, 4.3, 4.5) 채택은 아직 결정되지 않았다. 이번 구현은 그 항목들을 구현 설계의 전제로 받아 썼다. 채택이 뒤집히면 아래 "전제로 쓴 항목"의 코드를 다시 설계한다.
- 제품 숫자(역치, alpha, H, 도보 한계, 해상도, 판정 기 예산, 종류 순서)는 코드에 기본값이 없다. ActionConfig 생성자가 범위만 검증한다.
- 가상 경로(농수산물 / 범계)는 합성 픽스처이고 실노선 검증이 아니다.

## 티켓 매핑 (Jira, 2026-11-08 조회)

| 티켓 | 상태 | 이번 작업과의 관계 |
|---|---|---|
| SAL-362 | 완료 | 도보 후보 API. 이 체크아웃에는 코드가 없다. 어댑터 단계에서 dev 코드 확인이 필요하다. |
| SAL-365 / 366 | 해야 할 일 | 정류장 탑승 난이도 보더 스펙과 표시. |
| SAL-367 | 해야 할 일 | 증거 묶음 생성. 없으면 운영은 항상 보류(ABSTAIN)이다. |
| SAL-368 | 해야 할 일 | 판정 로직과 표정 UI를 플래그 기본 OFF로 dev에 추가(FE). 이번 도메인의 소비처이다. |
| SAL-378 | 신규 | 가려진 차량 ETA 복원 도메인(EtaChain). 이번 세션에서 등록했다. |

## 구현한 것 (backend/api-app, 패키지 salmon.domain)

| 파일 | 역할 |
|---|---|
| EtaChain | 같은 운행 차분, 시각 정렬, 구간 이어붙이기, 관측 차량 ETA 복원(SAL-378). |
| ActionModel | 시각과 지속시간 타입, Field(관측 / 파생 / 결측), 목록 3상태, 확률 입력과 슬롯, 평가 3상태, 일곱 사유 코드. |
| ActionConfig | 역치와 창 설정의 범위 검증. 기본값 없음. |
| PolicyEvaluator | 대기, 직접 도보, 재탑승의 실패 위험. 확률 결측에 0과 1을 대입해 값이 같을 때만 평가 가능으로 본다. 연결 여유와 연결 표지 계산. |
| ActionJudge | 설계 8.6 순서의 판정기. 후보 포트(CandidateSource)를 필요할 때만 호출한다. |
| ActionDecider | 판정 기 반복. 후보 조회가 기준선 시도열을 바꾸면 새 판정 기를 열고, 예산을 다 쓰면 기준선을 결측으로 봉인한다. |
| ActionGuidance | 사유 코드와 시도열에서 안내 문장을 만든다. |
| application/EtaFields | EtaChain 구간 결과를 판정 입력 Field로 바꾼다(도착 시각 변환은 호출하는 곳이 생길 때 만든다). 상계가 있으면 유한 상계, 없으면 상계 없음의 파생이고, 이어붙이지 못한 사유는 결측 사유로 옮긴다. |
| application/WalkOption, CandidateAssembler | SAL-362 응답(stationId, stopSequence, relation, 도보 초)을 받는 얇은 입력 타입과, 같은 방향(BEFORE, AFTER) 후보만 직접 도보 후보로 조립하는 함수. |
| application/ArrivalStopSource | 저장된 도착 스냅샷(ArrivalReadRepository)에서 노선과 정류장 방문의 대기 / 직접 도보용 목록을 만든다. 신선도(staleAt), 방문 검증, 분 해상도 양자화 상계를 처리한다. 창 덮임 규칙은 CoverageRule로 받고, 켜기 전까지 NONE(항상 덮임 모름)을 쓴다. |
| application/StopArrivalSource | 정류장 도착 목록 포트. StopArrivalSource.unavailable()은 소스가 없을 때 결측(NOT_PROVIDED)을 돌려준다. |
| application/DetourSource | 후보 우회량 포트. unavailable()은 값이 없는 결측을 돌려준다. |

테스트 85건(신규): ArrivalStopSourceTest 13, ActionJudgeTest 44, ActionOracleTest 4, ActionGuidanceTest 10, ActionDeciderTest 3, EtaFieldsTest 3, CandidateAssemblerTest 8. 여기에 EtaChainTest 19건을 더하면 salmon 패키지 테스트는 104건이다. EtaChain으로 만든 구간시간으로 가상 픽스처가 GO까지 가는 통합 테스트를 포함한다. 독립 오라클(LeafOracle)은 제품 코드를 가져오지 않고 잎을 펼쳐 합산하며, 확률 조합 243개와 대기 9개에서 판정기 값과 일치한다. 기준 파일 test-baseline.properties의 executed.api-app.test는 1165이다. Docker를 켠 전체 :api-app:check가 1165건으로 통과했다.

## 전제로 쓴 항목과 알려진 이탈

- 정류장 항목의 도착 시각이 파생이고 창 경계를 넘나들 수 있으면(점값은 창 밖, 보수 하한은 창 안) 설계 4.1 규칙 3은 슬롯이 결측일 때만 보류하라고 한다. 구현은 목록 쪽 경계 항목을 곧바로 근거 부족으로 본다(더 보수적). 재탑승 가지 쪽만 규칙 3대로 슬롯을 확인한다.
- 첫 시도 슬롯(S_FIRST)은 주변확률(MARG) 표지와 일치로 본다. 설계 12.1의 P-ON 표를 따른 해석이고 설계 본문에 명시되지는 않았다.
- 해상도는 준비 시각과 t2(그리고 t2 하한)에만 적용한다. 입력 도착 시각은 그대로 비교한다. H가 해상도의 배수가 아니면 H 안에 드는 t2는 해상도 격자 위의 값뿐이라, 재탑승 쪽에서 H는 해상도로 내림한 값처럼 동작한다.
- 재탑승 GO 안내는 맞은편 버스(포함 가지)마다 "그 차를 탔다면 지금부터 몇 분 뒤 차"를 보인다. 탈 확률이 0인 가지도 보이고, 문장은 맞은편 시도 문장과 같은 최대 2대까지다. 가지의 준비 시각(ready)은 결과에 담지만 지금 안내에서는 읽지 않는다. 화면 표시 개수와 문구는 SAL-368에서 정한다.
- 우회량 D는 입력으로 받고 허용 확정에 필수이다. 직접 도보 후보는 CandidateAssembler가 DetourSource에서 D를 받는다. 채택 명세 v1에는 D의 이름만 있다. 검토용 통합 명세 v2(2026-11-07) 7절은 D를 목표까지 계획한 차내 이동 시간의 합으로 제안하지만 채택 전이다. 채택 여부는 SAL-389에서 정한다. 실제 계산 구현은 없고 DetourSource.unavailable()만 있다. 따라서 운영 입력에서는 어떤 후보도 허용(ALLOWED)이 되지 못한다. 도보 합이 한도를 넘는 후보는 위반이고, 나머지는 허용 미확인이다. 모든 후보가 위반이면 판정은 NO_MOVE_INFEASIBLE이다.

## 아직 없는 것

- 도착 목록 포트의 실제 구현: 도착 API의 차량 식별과 운행 식별, 정보 시각, 덮임 정보가 없어 EtaChain.Observation을 만들 수 없다. 지금은 결측을 돌려주는 구현만 있다.
- SAL-362 연결: 코드는 dev에 머지됐지만(#251, 11-06) 이 체크아웃에는 없다. 응답을 WalkOption으로 바꾸는 호출부는 dev를 합친 뒤에 만든다. 재탑승 후보(반대편 도보, 하차 뒤 도보)는 SAL-362 응답만으로 조립할 수 없고 하차 정류장 결정이 먼저 필요하다.
- 탑승 확률 소스, 구간시간 이력.
- 공개 API 계약, 설정 적재와 기능 플래그(기본 OFF), 판정 기록 저장과 지표(WP8).
- 판정 보드 화면과 안내 연결(WP7, SAL-368), ON / OFF 통합 검증(WP9).
- 실제 현장 근거와 증거 묶음(SAL-367).

## 도착 API 조사 결과 (설계 14.3 ETA 소스 항목)

근거는 arrival 패키지와 common의 ArrivalSnapshot 계약을 읽은 것이다. 실행하거나 운영 데이터를 본 것은 아니다.

| 질문 | 확인한 답 |
|---|---|
| 정류장 방문 식별 | 있음. ArrivalVisit이 노선, 판본, stopSequence, 방향을 주고 반복 방문은 순번으로 구분한다. 매칭 실패는 targetVisit이 null(UNVERIFIED)이다. |
| 대수 제한 | 노선당 정류장당 ordinal 1 또는 2, 최대 두 대다. |
| 차량 식별 | Prediction.vehicleId는 문자열이고 null 검사가 없어 비어 있을 수 있다. 운행(trip) 식별자는 계약에 없다. ordinal은 운행 식별자가 아니다. |
| 정보 시각 | 정류장별 스냅샷의 receivedAt뿐이다. sourceObservedAt은 null이다. 예상 도착은 receivedAt에 남은 시간을 더한 근사값이다. |
| 예측 해상도 | 초 또는 분이다. 분 해상도면 양자화 오차가 최대 60초다. 도착 시각이 평균인지 분위수인지는 계약에 없다. |
| 창 덮임 | 계약에 없다. 단 두 대가 오고 둘째 도착이 H보다 늦으면 H 안은 덮인다고 볼 수 있다. 이 규칙은 아직 적용하지 않았다(결정 필요). |
| 신선도 | 스냅샷의 refreshAfterAt과 staleAt이 있다. |
| 읽기 경계 | ArrivalReadRepository는 저장된 최신 스냅샷만 읽고 수집을 요청하지 않는다. 후보 정류장의 스냅샷이 없을 수 있다(NOT_COLLECTED). |

결론: 대기와 직접 도보용 목록(StopList)은 지금 필드로 만들 수 있다. EtaChain.Run에 필요한 운행 식별자와 같은 정보 시각의 차분 재료는 지금 소스에 없다. 정류장마다 스냅샷 수신 시각이 달라 같은 운행 차분(sameRun)은 INFORMATION_MISMATCH가 되고, 정렬(align)은 갱신 오차 상계의 근거가 없어 NO_ERROR_BOUND가 된다.

## 루트 의견 반영 (도착 API 조사의 결정 세 가지)

| 결정 | 처리 |
|---|---|
| 창 덮임 규칙 | (11-08 02:25 KST 운영 SSH 접속은 시간 초과로 실패했다. 추출은 접속 가능한 네트워크에서 다시 한다.) CoverageRule.SECOND_BEYOND_WINDOW로 구현했지만 기본은 NONE(꺼짐)이다. 켜기 전에 docs/salmon-action/scripts/check_arrival_assumptions.py로 저장된 스냅샷을 확인한다. 로컬에는 도착 스냅샷 자료가 없고(ETA 추출은 SSH 시간 초과로 비었다) 운영 DB 조회는 승인이 필요해서 스크립트는 합성 자료 자체 검증(--self-test)만 통과했다. |
| 분 해상도 오차 | Observed에 양자화 상계(quantization)를 붙였다. Derived로 바꾸지 않아 시각 근거(timeBasis)는 DIRECT로 남고, 연결 표지만 오차 범위를 반영한다. Observed의 상계가 없으면 오차를 따로 모르는 것이 아니라 0으로 읽는다. |
| 빈 vehicleId | 항목을 지우지 않고 목록에 남긴다. 항목 ID는 스냅샷 ID, 노선, ordinal로 만들고 차량 ID를 쓰지 않는다. EtaChain 재료에서 빠진다(운행 식별자도 없다). |

## 어댑터 루트 리뷰 반영 (11-08)

- 판정 시각 0은 한 번만 정한다. StopArrivalSource.arrivalsAt이 호출자가 준 decisionAt을 받고, 신선도와 오프셋 모두 그 시각 기준이다(이전에는 호출마다 시계를 읽었다).
- 창 길이 H는 ActionConfig에서 받는다. ArrivalStopSource가 따로 들고 있지 않는다.
- 호출하는 곳이 없던 EtaFields.arrival을 지웠다.
- 정보 시각 불일치를 별도 결측 사유(INFORMATION_TIME_MISMATCH)로 분리했고, 상수만 돌려주던 클래스는 StopArrivalSource.unavailable() 정적 팩토리로 바꿨다. 와이어 문자열 파싱은 WalkOption.Relation.parse로 옮겼다.
- 열어 둔 질문: 초 해상도 도착도 수신 시각 근사 오차가 있는데 Observed 상계를 0으로 읽는다. 이 오차를 상계로 둘지 정해지지 않았다.

## 확인 스크립트 실행 방법

1. 접속 가능한 네트워크에서 도착 스냅샷 추출 스크립트(eta_extract_arrivals.sh)를 출퇴근 시간(KST 07시부터 10시, 17시부터 20시) 밖에 읽기 전용으로 실행한다. 사용법은 스크립트 머리말에 있다. 평일 낮 1시간 창부터 시작한다.
2. `python3 -I docs/salmon-action/scripts/check_arrival_assumptions.py --csv arrivals.csv.gz --window-minutes 15`를 실행한다.
3. 출력은 집계 숫자뿐이다(순서 어긋난 쌍, 빈 차량 ID, 해상도 비율, 둘째 도착이 창 안일 때 숨은 차량이 보인 비율, 규칙 위반 비율). 규칙 위반 비율은 각 스냅샷 쌍의 첫 수신 시각을 판정 시각으로 가정한 근사치다. 실제 판정 시각이 더 늦으면 비율이 달라진다. 원자료 행과 차량 식별자는 대화에 올리지 않는다.
   규칙 위반 비율은 세 가지를 세지 못한다. 그래서 실제보다 낮게 나올 수 있다.
   - 숨은 차량은 나중 스냅샷의 예측에 차량 ID가 있을 때만 센다. 차량 ID가 빈 예측은 숨은 차량이어도 위반으로 세지 않는다. 출력의 빈 차량 ID 수를 함께 보고 해석한다.
   - 수신 간격이 120초 이내인 스냅샷 쌍만 비교한다(PAIR_GAP_LIMIT). 간격이 더 긴 쌍 사이에 나타난 차량은 보지 않는다.
   - 창 끝에서 60초 안쪽에 도착하는 예측은 위반으로 세지 않는다(SLACK).
4. 규칙 위반 비율이 낮을 때만 CoverageRule을 SECOND_BEYOND_WINDOW로 켜는 것을 검토한다. 얼마가 낮은지는 정해지지 않았다.

## 검증

- 2026-11-08 첫 구현: salmon 패키지 테스트, ArrivalPredictionTest, verifyTestResourceRules가 통과했다(JDK 21). 이때 체크아웃은 dev보다 오래됐고 Docker가 없어 전체 `:api-app:check`는 돌리지 못했다.
- 2026-11-10: dev 기준 브랜치에서 Docker를 켜고 전체 `:api-app:check`를 돌려 1165건(salmon 104건)이 통과했다. test-baseline.properties의 executed.api-app.test는 1165다.
- 리뷰에서 찾아 고친 버그는 네 건이다. 모두 고치기 전에 새 테스트가 실패하고, 고친 뒤 통과함을 확인했다.
  1. 경계 가지에 시도할 차가 있는데 확률 슬롯이 없으면 확인된 공집합(위험 1)으로 돌려주던 문제.
  2. EtaChain.restore가 상쇄되는 기준 관측의 오차를 한 번 더 더하던 문제. 같은 운행 구간 하나를 쓰고 두 관측이 분 단위 예측이면 오차를 필요한 값의 세 배로 잡았다.
  3. t2는 해상도로 올림하는데 t2 하한은 올림하지 않아, 오차가 없는 가지도 경계 가지로 분류해 판정할 수 있는 후보를 근거 부족으로 빼던 문제.
  4. 재탑승 목록이 창을 덮지 않아 판정하지 못할 때 근거 부족의 사유 목록이 비던 문제.
