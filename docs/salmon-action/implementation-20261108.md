# 연어행동 구현 기록

## 범위와 지위

- 규범은 SALMON-MATH-v1.md(성공은 원하는 방향 버스에 실제 탑승)이고 구현 기준은 SALMON-ACTION-IMPLEMENTATION-DESIGN-v1.md이다.
- 설계 11.1의 선행 조건인 권고와 제안 해소(Q1, 4.1, 4.2, 4.3, 4.5) 채택은 아직 결정되지 않았다. 구현은 그 항목들을 전제로 받아 썼다. 채택 결과가 다르면 아래 표의 코드를 다시 설계한다.

| 항목 | 내용 | 영향받는 코드 |
|---|---|---|
| Q1 | 주변확률(MARG) 입력도 평가 가능으로 보고 가정 표지를 INDEP로 단다 | domain/Probabilities(ProbabilitySupply), domain/PolicyEvaluator(Resolver) |
| 4.1 | 값에 영향이 없는 결측은 평가를 막지 않는다(결측 확률에 0과 1을 대입해 값이 같으면 평가 가능) | domain/PolicyEvaluator(solve) |
| 4.2 | 후보 조회가 기준선을 바꾸면 판정을 버리고 새 판정 기로 다시 시작한다 | domain/ActionDecider |
| 4.3 | 연결 여유 단계는 후보를 빼지 않고 여유와 표지만 기록한다 | domain/PolicyEvaluator(연결 여유, 연결 표지) |
| 4.5 | 곱 구조의 가정 표지와 시각 근거 표지를 따로 둔다 | domain/Verdicts(Assumption, TimeBasis) |

- 제품 숫자(역치, alpha, H, 도보 한계, 해상도, 판정 기 예산, 종류 순서)는 코드에 기본값이 없다. ActionConfig는 값의 유효성만 검사한다. 값은 SAL-389에서 정한다.
- 가상 경로(농수산물, 범계)는 합성 픽스처이고 실노선 검증이 아니다.

## 티켓

| 티켓 | 이 코드와의 관계 |
|---|---|
| SAL-378 | 이 PR. 가려진 차량 ETA 복원(EtaChain), 판정 코어, 도착 어댑터, 맞은편 버스별 재탑승 안내(SAL-391), 우회량 포트(SAL-386)를 포함한다 |
| SAL-386, 388 | 우회량 계산, SAL-362 후보 응답 연결과 재탑승 후보 조립 |
| SAL-387 | 도착 정보에 운행 식별자와 예측 생성 시각이 없음을 확인한 조사(PR #275) |
| SAL-389 | 설정값과 미결 설계 규칙 결정 |
| SAL-390 | 창 덮임 가정 확인(운영 스냅샷, 위치 목록 방식 검토) |
| SAL-392 | GO 현실성 측정과 추천 몰림 영향 |
| SAL-367 | 증거 묶음 생성 |
| SAL-368 | 화면 연결. 안내 문구와 보이는 개수를 여기서 정한다 |

## 구현한 것 (backend/api-app, 패키지 salmon)

| 파일 | 역할 |
|---|---|
| domain/EtaChain | 같은 운행 차분, 시각 정렬, 구간 이어붙이기, 관측 차량 ETA 복원. 이어붙이지 못한 결과는 Unresolved(사유)이다. |
| domain/Timing | 시각 0부터의 오프셋(Offset)과 지속시간(Span). |
| domain/Fields | Field(관측, 파생, 결측), 오차 상계(Finite, NoBound), 결측 사유(MissingReason). |
| domain/StopLists | 정류장 도착 항목과 목록 3상태(Queried, RestoredOnly, Unavailable), 창 덮임. |
| domain/Probabilities | 확률 입력과 표지, 시도 슬롯과 조건 이력, 슬롯에 맞는 값을 고르는 ProbabilitySupply. |
| domain/Candidates | 직접 도보와 재탑승 후보, 허용 상태(Admissibility). |
| domain/Verdicts | 평가 3상태(Evaluable, ConfirmedEmpty, Insufficient), 판정하지 못한 이유(Gap, GapKind), 시도와 재탑승 가지, 결과(Outcome), 일곱 사유 코드, 최종 판정(Verdict). |
| domain/Requirements | 위 타입이 함께 쓰는 식별자, 유한값, 우회량 검증(패키지 내부). |
| domain/ActionConfig | 역치와 창 설정의 유효성 검사. 기본값 없음. |
| domain/PolicyEvaluator | 대기, 직접 도보, 재탑승의 실패 위험. 확률 결측에 0과 1을 대입해 값이 같을 때만 평가 가능으로 본다. 연결 여유와 연결 표지 계산. |
| domain/ActionJudge | 설계 8.6 순서의 판정기. 판정 기 한 번만 다룬다. 후보 포트(CandidateSource)를 필요할 때만 호출한다. 후보 조회가 기준선 시도열을 바꾸면 후보를 평가하지 않고 바뀐 기준선(BaselineChanged)을 돌려주고, 바꾸지 않으면 확정 판정(Settled)을 돌려준다. 판정 진입점은 패키지 안에만 열려 있다. |
| domain/ActionDecider | 제품 코드가 부르는 판정 진입점(decide). 판정 기를 반복한다. 판정기가 바뀐 기준선을 돌려주면 그 기준선으로 새 판정 기를 열고, 예산을 다 쓰면 기준선을 결측(BASELINE_UNSTABLE)으로 두고 한 번 더 판정한다. 이 마지막 판정은 대기 위험이 없어 후보를 조회하지 않고 끝난다. 기준선 소스가 이 결측 기준선을 쓰지 않아 기준선이 또 바뀌면 IllegalStateException을 던진다. |
| guidance/ActionGuidance | 사유 코드와 시도열에서 안내 문장을 만든다. 도메인 밖에 두었고, 문구와 보이는 시도 수는 SAL-368에서 정한다. |
| application/EtaFields | EtaChain 구간 결과를 판정 입력 Field로 바꾼다(도착 시각 변환은 호출하는 곳이 생길 때 만든다). 상계가 있으면 유한 상계, 없으면 상계 없음의 파생이고, 이어붙이지 못한 사유는 결측 사유로 옮긴다. |
| application/WalkOption, CandidateAssembler | SAL-362 응답(stationId, stopSequence, relation, 도보 초)을 받는 얇은 입력 타입과, 같은 방향(BEFORE, AFTER) 후보만 직접 도보 후보로 조립하는 함수. |
| application/ArrivalStopSource | 저장된 도착 스냅샷(ArrivalReadRepository)에서 노선과 정류장 방문의 대기, 직접 도보용 목록을 만든다. 아래 "도착 어댑터 규칙" 참고. |
| application/StopArrivalSource | 정류장 도착 목록 포트. StopArrivalSource.unavailable()은 소스가 없을 때 결측(NOT_PROVIDED)을 돌려준다. |
| application/DetourSource | 후보 우회량 포트. unavailable()은 값이 없는 결측을 돌려준다. |

판정하지 못한 이유는 Gap(종류, 목록 역할, 대상, 결측 사유)로 남긴다. 목록 역할(ListRole)은 CURRENT, WALK, OPPOSITE, REBOARD 중 하나이고, 목록 종류(LIST_*)와 ARRIVAL_UNKNOWN에만 붙고 나머지 종류에서는 비어 있다. 대상은 언제나 id 하나다. 목록 종류면 후보 id(현재 정류장 목록이면 current-stop), ARRIVAL_UNKNOWN이면 도착을 모르는 항목 id, PROBABILITY_UNKNOWN이면 시도 id, 그 밖에는 후보 id다. 문자열 접두사로 목록을 구분하지 않아 판정 기록과 지표에서 종류와 목록 역할별로 셀 수 있다. 재탑승 후보의 경계 가지는 확률이 없는 시도마다 PROBABILITY_UNKNOWN을 하나씩 남기므로 Gap 수가 후보 수보다 많을 수 있다. 판정과 위험도는 이 수에 영향을 받지 않는다.

## 전제로 쓴 항목과 알려진 이탈

- 정류장 항목의 도착 시각이 파생이고 창 경계를 넘나들 수 있으면(점값은 창 밖, 보수 하한은 창 안) 설계 4.1 규칙 3은 슬롯이 결측일 때만 보류하라고 한다. 구현은 목록 쪽 경계 항목을 곧바로 근거 부족으로 본다(더 보수적). 재탑승 가지 쪽만 규칙 3대로 슬롯을 확인한다.
- 첫 시도 슬롯(S_FIRST)은 주변확률(MARG) 표지와 일치로 본다. 설계 12.1의 P-ON 표를 따른 해석이고 설계 본문에 명시되지는 않았다.
- 확률 입력은 슬롯과 조건 이력이 같은 값을 먼저 쓰고, 없을 때만 조건 이력 없는 주변확률을 쓴다. 조건 이력이 붙은 값은 슬롯의 조건 이력과 완전히 같을 때(판정 기, 앞선 실패, 미시도, 맞은편에서 탄 차)만 쓴다. 그래서 재탑승의 한 가지에 고정한 값은 다른 가지 슬롯에 들어가지 않는다. 주변확률은 첫 시도 슬롯에서는 일치로, 그 밖의 슬롯에서는 독립 가정(INDEP)으로 들어간다. 명세 7.2절은 가지가 다르면 다른 값이라는 것까지만 정하고, 조건 이력이 붙은 주변확률을 그 가지의 어느 슬롯까지 쓸지는 정하지 않는다. 구현은 가장 좁은 규칙(완전 일치)을 쓴다.
- 해상도는 준비 시각과 t2(그리고 t2 하한)에만 적용한다. 입력 도착 시각은 그대로 비교한다. H가 해상도의 배수가 아니면 H 안에 드는 t2는 해상도 격자 위의 값뿐이라, 재탑승 쪽에서 H는 해상도로 내림한 값처럼 동작한다.
- 재탑승 GO 안내는 맞은편 버스(포함 가지)마다 "그 차를 탔다면 지금부터 몇 분 뒤 차"를 보인다. 탈 확률이 0인 가지도 보이고, 문장은 맞은편 시도 문장과 같은 최대 2대까지다. 가지의 준비 시각(ready)은 결과에 담지만 지금 안내에서는 읽지 않는다.
- 우회량 D는 입력으로 받고 허용 확정에 필수이다. 직접 도보 후보는 CandidateAssembler가 DetourSource에서 D를 받는다. v1 ActionJudge 경로는 DetourSource.unavailable()만 있어 운영 입력에서 후보가 ALLOWED가 되지 못한다. 도보 합이 한도를 넘는 후보는 위반이고, 나머지는 허용 미확인이다. 모든 후보가 위반이면 v1 판정은 NO_MOVE_INFEASIBLE이다.
- 통합 명세 v2는 2026-11-10에 이번 연어행동 판정의 기준으로 채택했다. D는 목표 또는 계획 하차까지의 계획된 차내 이동 시간이고 대기 시간은 넣지 않는다. 운영 숫자(H, 도보 상한, D 상한, 환승, 이득 역치, eta)는 SAL-389에 남긴다. v2 판정은 IntegratedJudge다. 차내 이동 시간이 없는 후보는 ALLOWED가 될 수 없다. 현장 확률과 운행 식별자가 없으면 GO를 만들지 않는다.
- ActionDecider는 판정 기 식별자를 `sources.baseline(epoch, ...)`로 넘긴다. 확률 소스는 ConditionHistory.epoch에 이 문자열을 직접 만들지 말고 받은 값을 그대로 넣는다. 그래야 조건 이력이 정확 일치로 맞는다.

## 이 브랜치로 모은 하위 작업

하위 워크트리의 커밋을 그대로 merge하지 않았다. SAL-395는 dev에 이미 있고, SAL-396과 SAL-392는 그 앞의 재작성 이력을 같이 가져온다. 파일만 현재 v2 트리에 올렸다.

- SAL-396: 좌석 예보로 탑승 확률 입력을 만드는 SeatForecastProbabilitySource. v1 판정 타입에 근사 표지를 더했다.
- SAL-392: 오프라인 실행기(`runSalmonOffline`)와 입력 추출 스크립트.
- SAL-390: 도착 스냅샷 보존 비용 스크립트와 호출 한도 배수 테스트. 제품 조회가 아니다.
- SAL-388: WalkOptions, ReboardAssembly, ActionDecision. 하차 구간은 호출자가 준 목록만 조립한다. 조회 API는 이 클래스를 부르지 않는다.
- 하차 정류장 규칙: AlightingStopRule. 경계 설명은 docs/salmon-action/alighting-stop-boundary.md.
- 조회 보조 타입: SalmonStop, DecisionLog, MemoryDecisionLog, SalmonDecisionRecord, SalmonCandidateRoutes, AbsentCandidateRoutes, BaselineProbabilitySource, SalmonActionOutcome, SalmonActionException. 스프링 빈으로 등록하지 않았다.

통합 워크트리가 v1 ActionJudge와 PolicyEvaluator를 크게 바꾼 부분은 가져오지 않았다. 공개 판정은 IntegratedJudge다.

## 아직 없는 것

- 도착 목록에서 EtaChain 입력을 만드는 변환: 도착 정보에 운행 식별자와 예측 생성 시각이 없다(SAL-387, PR #275).
- 우회량 계산(SAL-386). D의 운영 정의와 숫자는 SAL-389.
- 공개 조회는 `GET /api/v1/routes/{routeId}/stations/{stationId}/salmon-action`이다. `salmon.action.enabled` 기본값은 false다. 꺼지면 kind만 DISABLED다. 켜지면 마감(요청 시각 + horizon) 안에 그 정류장에 닿는 차량을 도착 예측에서 찾고, 전광판에서 그 차량들의 출발 만차 확률을 읽어 대기 위험 범위를 만든다. 한 대면 그 확률이고, 둘 이상이면 곱하지 않고 주변확률만으로 정해지는 범위(상한은 가장 작은 확률)다. 도착 목록의 두 대가 모두 마감 안이면, 전광판에만 보이는 뒤 차량의 도착 시각을 맨 앞 차량이 있는 정류장의 도착 목록에서 이어 붙인다. 이때 그 정류장의 도착 조회를 요청한다. 이 이어 붙이기는 EtaChain을 쓰지 않는 근사이고, 맨 앞 차량이 그 정류장을 이미 떠났을 수 있어 정류장 한 구간의 평균 시간을 여유로 둔다. 마감 안에 닿는 차량이 없거나 그 가운데 확률이 없는 차량이 있으면 ABSTAIN_EVIDENCE다. 확률은 DEPARTURE_ZERO 예보만 쓴다. 전광판이 FIRST_POST_STOP_ZERO 예보만 내는 동안에는 켜도 항상 ABSTAIN_EVIDENCE다. 이동 후보는 같은 방향 도보만 보고, 차내 시간이 없어 GO로 닫지 않는다. 문장은 사용자가 할 행동이 정해진 상태(GO, NO_MOVE_*)에만 싣는다. ABSTAIN_*, INVALID_INPUT, WINDOW_EXPIRED는 reasonCode만 싣는다. 노선 ID, 정류장 ID, 순번의 형식이 틀리면 400이다. 확률은 응답에 넣지 않는다.
- 판정 기록 저장과 지표(WP8)는 아직 없다.
- 판정보드는 선택한 정류장에 서버 문장만 보여 준다. 꺼짐, 첫 응답 전, 조회 실패, 문장 없는 판정, 계약 밖 응답은 숨긴다. 서버가 꺼져 있다고 답하면 그 화면에서는 다시 조회하지 않는다. 브라우저에서 켠 상태의 문장 표시는 아직 확인하지 않았다.
- 실제 현장 근거와 증거 묶음(SAL-367).

## 도착 정보로 만들 수 있는 것 (설계 14.3 ETA 소스 항목)

근거는 arrival 패키지와 common의 ArrivalSnapshot 계약이다.

| 질문 | 답 |
|---|---|
| 정류장 방문 식별 | 있음. ArrivalVisit이 노선, 판본, stopSequence, 방향을 주고 반복 방문은 순번으로 구분한다. 매칭 실패는 targetVisit이 null(UNVERIFIED)이다. |
| 대수 제한 | 노선당 정류장당 ordinal 1 또는 2, 최대 두 대다. |
| 차량 식별 | Prediction.vehicleId는 문자열이고 비어 있을 수 있다. 운행(trip) 식별자는 계약에 없다. ordinal은 운행 식별자가 아니다. |
| 정보 시각 | 정류장별 스냅샷의 receivedAt뿐이다. sourceObservedAt은 null이다. 예상 도착은 receivedAt에 남은 시간을 더한 근사값이다. |
| 예측 해상도 | 초 또는 분이다. 분 해상도면 양자화 오차가 최대 60초다. 도착 시각이 평균인지 분위수인지는 계약에 없다. |
| 창 덮임 | 계약에 없다. 아래 창 덮임 규칙으로 판단하고, 기본은 꺼짐이다. |
| 신선도 | 스냅샷의 refreshAfterAt과 staleAt이 있다. |
| 읽기 경계 | ArrivalReadRepository는 저장된 최신 스냅샷만 읽고 수집을 요청하지 않는다. 후보 정류장의 스냅샷이 없을 수 있다. |

대기와 직접 도보용 목록(StopList)은 지금 필드로 만들 수 있다. EtaChain.Run에 필요한 운행 식별자와 같은 정보 시각의 차분 재료는 지금 소스에 없다.

## 도착 어댑터 규칙

- 판정 시각은 호출자가 한 번 정해 넘긴다(StopArrivalSource.arrivalsAt의 decisionAt). 신선도와 오프셋 모두 그 시각 기준이다.
- 창 길이 H는 ActionConfig에서 받는다.
- 저장된 스냅샷이 없으면 NOT_PROVIDED, staleAt이 지났으면 STALE, 정류장 순번을 확인하지 못하면 UNVERIFIED_SEQUENCE다. 셋 다 도착 시각을 만들지 않는다.
- 분 해상도 예측은 관측값(Observed)에 양자화 상계 60초를 붙인다. 파생(Derived)으로 바꾸지 않아 시각 근거는 DIRECT로 남고, 연결 표지만 오차 범위를 반영한다. 초 해상도 예측은 상계를 0으로 읽는다. 이 수신 시각 근사 오차를 상계로 둘지는 SAL-389에서 정한다.
- 빈 vehicleId도 항목을 지우지 않는다. 항목 ID는 스냅샷 ID, 노선, 정류장 방문 순번(stopSequence), ordinal로 만들고 차량 ID를 쓰지 않는다. 스냅샷이 정류장 단위라, 한 노선이 같은 정류장을 두 번 지나면 방문 순번이 두 방문의 항목을 구분한다. 확률 소스는 이 ID를 문자열로 조립하지 말고 도착 목록에서 받은 값을 그대로 쓴다.
- 저장소 조회가 DataAccessException을 던지면 QUERY_FAILED다.
- 창 덮임 규칙(CoverageRule.SECOND_BEYOND_WINDOW)은 목록이 정확히 두 대이고 둘째 도착이 H보다 늦으면 창을 덮었다고 본다. 기본은 NONE(꺼짐)이다. 켜기 전 확인과 위치 목록 방식 검토는 SAL-390에서 한다.

## 확인 스크립트 실행 방법

1. 운영 DB에서 도착 스냅샷을 읽기 전용으로 추출한다. 운영 조회 승인이 필요하고, 출퇴근 시간(KST 07시부터 10시, 17시부터 20시) 밖에 실행한다. 평일 낮 1시간 창부터 시작한다.
2. `python3 -I docs/salmon-action/scripts/check_arrival_assumptions.py --csv arrivals.csv.gz --window-minutes 15`를 실행한다.
3. 출력은 집계 숫자뿐이다(순서 어긋난 쌍, 빈 차량 ID, 해상도 비율, 둘째 도착이 창 안일 때 숨은 차량이 보인 비율, 규칙 위반 비율). 규칙 위반 비율은 각 스냅샷 쌍의 첫 수신 시각을 판정 시각으로 가정한 근사치다. 실제 판정 시각이 더 늦으면 비율이 달라진다. 원자료 행과 차량 식별자는 공유하지 않는다.
   규칙 위반 비율은 세 가지를 세지 못한다. 그래서 실제보다 낮게 나올 수 있다.
   - 숨은 차량은 나중 스냅샷의 예측에 차량 ID가 있을 때만 센다. 차량 ID가 빈 예측은 숨은 차량이어도 위반으로 세지 않는다. 출력의 빈 차량 ID 수를 함께 보고 해석한다.
   - 수신 간격이 120초 이내인 스냅샷 쌍만 비교한다(PAIR_GAP_LIMIT). 간격이 더 긴 쌍 사이에 나타난 차량은 보지 않는다.
   - 창 끝에서 60초 안쪽에 도착하는 예측은 위반으로 세지 않는다(SLACK).
4. 규칙 위반 비율이 낮을 때만 CoverageRule을 SECOND_BEYOND_WINDOW로 켜는 것을 검토한다. 기준값은 SAL-389에서 정한다.

`python3 -I docs/salmon-action/scripts/check_arrival_assumptions.py --self-test`는 합성 자료로 스크립트 자체를 검증한다.

## 검증

- 테스트 85건(신규): ArrivalStopSourceTest 13, ActionJudgeTest 44, ActionOracleTest 4, ActionGuidanceTest 10, ActionDeciderTest 3, EtaFieldsTest 3, CandidateAssemblerTest 8. 여기에 EtaChainTest 19건을 더하면 salmon 패키지 테스트는 104건이다.
- 독립 오라클(LeafOracle)은 제품 코드를 가져오지 않고 잎을 펼쳐 합산하며, 확률 조합 243개와 대기 9개에서 판정기 값과 일치한다. EtaChain으로 만든 구간시간으로 가상 픽스처가 GO까지 가는 통합 테스트도 있다.
- Docker를 켠 전체 `:api-app:check`가 1165건(salmon 104건)으로 통과한다. test-baseline.properties의 executed.api-app.test는 1165다.
- 테스트로 고정한 경계 사례는 다음과 같다. 각각 고치기 전에는 실패하는 테스트였다.
  1. 경계 가지에 시도할 차가 있는데 확률 슬롯이 없으면 확인된 공집합(위험 1)이 아니라 근거 부족이다.
  2. EtaChain.restore는 상쇄되는 기준 관측의 오차를 더하지 않는다. 같은 운행 구간 하나에서 두 관측이 분 단위 예측이면 오차는 60초다.
  3. t2 하한도 해상도로 올림해서, 오차가 없는 가지를 경계 가지로 분류하지 않는다.
  4. 재탑승 목록이 창을 덮지 않아 판정하지 못하면 LIST_NOT_COVERING_WINDOW 사유를 남긴다.
