# SAL-359: 20개 노선 운영 적재 준비 완료

기준 커밋 `62884541`. 기존 모델의 가중치와 통계는 완성되어 있었고, 이번 변경은 운영 로더와 번들 포장에 남아 있던 기존 8개 노선 조건을 새 입력 판본에 맞춘다. 운영 DB 변경, 모델 활성화, push는 하지 않았다.

## 변경

- 기존 `les-original-v1` 판본은 기존 8개 노선 허용 조건과 검증을 유지한다.
- `les-confirmed-closure-v2` 판본은 통계, 운영 설정, 검증 결과의 노선 목록이 순서까지 같아야 적재한다. 고정 8개 조건만 제거하거나 임의의 노선을 무조건 허용하지 않는다.
- 모든 노선의 통계 입력 판본, 정산 판본과 정원 기준을 검사한다. 원래 모델 파일의 해시와 검증 결과가 가리키는 모델 해시도 검사한다.
- 수치 비교, Worker 재생, 정산과 로컬 런타임 검사 결과를 운영 번들에 보존한다. PASS 표기뿐 아니라 원본 결과의 사례 수, 누락 노선, 입력/예측/정산 오차와 근거 불일치를 검사한다. 다른 모델의 보고서나 일부 노선 결과로 전체 노선을 승인하지 않는다.
- 학습이나 Worker 계산식은 변경하지 않았다. 원래 번들의 모든 선언 파일을 바이트 단위로 유지한다. 새 manifest와 검증 증거만 운영 포장에 포함한다.

## 실제 산출물

- 출시 ID: `les-confirmed-20routes-20261105`
- 로컬 자료 루트: `/Users/goo/.local/share/codex/salmonbus-model-handoff-20261104/causal-20routes-20261105`
- 원본: `worker-bundle-20261105` (비활성 원본 보존)
- 운영용 번들: `serving-release-20261105`
- 검증 결과 변환본: `serving-receipts-20261105`
- 원본 manifest SHA-256: `ebf96ce1836ae2347027803918bad2de9209c36da0879a359bb9948b6be4a220`
- 운영 manifest SHA-256: `14d881e90bfec4514dd657a2df6738a70cd237881774a123919c622a4ba82273`

운영 manifest의 `serving_ready=true`는 적재 검증을 통과한 포장이라는 뜻이다. 운영 DB에서 활성 모델을 바꿨다는 뜻은 아니다. 원본 feature-contract 안의 기존 상태 표시는 원본 해시 보존을 위해 그대로 남으며, 적재 승인은 운영 manifest와 연결된 검증 결과로 판단한다.

## 검증

- Python 포장/거부 조건 테스트 10개 통과.
- Java `*Les*Test` 101개 통과. 이번에 작성한 거부 조건/판본 검증은 15개이고 기존 검증 86개도 통과했다.
- 실제 운영 파일 진입점인 `FileModelBundleLoader.filesUnder(...).load()`로 새 번들을 읽었다. 이 경로가 `LesServingBundle.load()`와 파일 해시, 입력 계약, 검증 증거, 기준 예측 계산을 검사한다.
- 전체 20개 노선에 대해 GBIS 노선 ID, 정류장 목록, 방향과 승차 가능 여부가 모델 통계와 맞았다. 메모리 registry에 등록한 모델과 배포 식별의 일치도 확인했다.
- 같은 가중치/통계를 검증한 기존 수치 대조 3,041건, 실제 Worker 예보 220건과 정산 440건 결과를 재사용했다. 이번 포장 작업을 새 운영 실험으로 표시하지 않는다.
- 최종 Java 로그: `serving-precommit-final.log`. 운영 파일 진입점 검사: `serving-file-loader-final.log`, 결과 `serving-file-loader-final-20261105.json`.
- 집계 기록은 `20-route-serving-release-result-20261105.json`에 보존한다. 전체 CI 재실행은 기존 사용자 지시에 따라 생략했다.

## 재생성

`backend/tools/les_model/prepare_confirmed_receipts.py`는 원본 모델과 실제 수치/Worker/후보 런타임 보고서를 검사하고 검증 증거를 묶는다. `prepare_serving_bundle.py`는 이 증거로 새 운영 번들을 만든다. 기존 출력 디렉터리는 덮어쓰지 않는다.

```sh
# backend에서 실행. BUNDLE과 OUTPUT은 팀원의 절대 경로로 지정한다.
nice -n 10 ./gradlew :forecasting:verifyLesServingReadiness \
  -PlesReadinessMode=serving -PlesBundle="$BUNDLE" \
  -PlesReadinessOutput="$OUTPUT" -PlesReleaseId=les-confirmed-20routes-20261105 \
  --no-daemon --no-parallel --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1g -Xms128m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=1'
```

이 검사는 운영 DB나 외부 API에 접근하지 않으며 운영키가 필요 없다. 원자료와 큰 모델 파일은 Git에 포함하지 않는다.

## 운영 적용 시 구분할 점

이 변경이 포함된 코드를 배포한 뒤 새 모델 디렉터리를 `MODEL_BUNDLE_DIRECTORY`로 지정해야 한다. 모델 파일 적재와 DB 활성 배포 선택은 기존 절차에 따라 별개로 수행한다. 현재 코드는 배포되지 않았고 새 모델도 운영에서 선택되지 않았다.

기존 Worker 검증의 자정 이전 상태는 Python에서 재구성한 체크포인트였다. 운영 초기 상태 준비와 실제 부하 측정은 이번 로컬 적재 검사 범위에 포함하지 않는다. 당시 품질 제외 이력이 없는 점, 하차 후 좌석 정답이 직접 측정한 승하차 인원이 아니라 추정값이라는 점도 유지된다.

## PR 게시 전 컨벤션 재점검

2026-11-05 Confluence BE Java 컨벤션 v6을 다시 확인했다. 변경 Java 103개를 구문 분석해 변경 범위의 금지 문법, import, 테스트명과 매개변수 서식을 확인하고 누락된 닫는 괄호 줄바꿈과 import 정렬을 보완했다. record의 암묵적 생성자 매개변수는 검사 대상에서 구분했다. 새 BE Java의 재할당 없는 원시 변수/매개변수 final 누락은 0건이었다.

보완 전후 모든 Java 타입 선언의 AST(구문 트리)가 동일함을 비교했다. import 순서와 공백 외에 계산, 문자열, SQL, 외부 계약을 변경하지 않았다. 관련 Les 테스트 101개와 API/유지보수/수집 모듈의 테스트 코드 컴파일이 통과했다. 전체 CI는 사용자 요청에 따라 원격 PR에서 확인한다. 로컬 검증 로그는 `/tmp/sal359-verification/prepush-style-tests.log`다.

PR #250의 첫 원격 SHARED-DEV-TOOLS-CI에서 초기화 코드의 고정 해시 검사가 실패했다. 원인은 LocalData의 명시적 초기화 대상에 새 레스 정산 테이블 세 개를 포함하면서 준비 계약이 바뀌었지만, 검토한 전환 목록은 이전 해시를 유지한 것이다. 기존 초기 데이터와 기존 dev 모델 파일의 변경은 없음을 diff로 확인했다.

기존 네 판본의 전환은 보존하고 새 준비 해시 `17a98f6b1ac235c9d77dc59823cd55974dcdc5c045a879c77e81f6b074df7616`로의 정방향 전환을 명시했다. 알 수 없는 판본, 같은 판본, 역방향, 계약 누락은 거부한다. 허용된 전환이어도 초기 노선 데이터가 바뀌면 기존 승인 검사로 거부하는 테스트를 유지했다. 실제 준비 코드 해시 검사도 새 명시 판본과 대조하며 제거하지 않았다. CompatiblePreparationTest 4개가 통과했다. 새 테스트는 모듈에 기존 의존성이 없던 AssertJ를 backend와 같은 3.27.7로 사용한다.
