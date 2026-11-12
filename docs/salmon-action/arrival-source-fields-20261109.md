# SAL-387 도착 정보의 운행 식별자와 예측 생성 시각 확인

## 1. 목적과 결론

가려진 차량 ETA 복원에 필요한 운행 식별자와 예측 생성 시각이 원문 응답에 있는데 파싱에서 빠지는지 확인한다. 예측 생성 시각은 공급자가 그 도착 예측을 계산한 시각이고, SAL-387 제목의 원천 관측 시각과 같은 대상이다. 확인한 도착 응답 자료에도, 공급자 응답 항목 명세([경기버스정보 개발가이드의 버스도착정보목록조회](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrivalStation))에도 두 필드가 없다. 응답 머리의 `queryTime`은 명세상 요청 처리 시각이고, 이미 `ArrivalSnapshot.sourceQueryTime`으로 저장한다. 그래서 이 소스로는 같은 운행과 같은 정보 시각을 보장할 수 없어 제품 코드, 저장 계약, 마이그레이션, 테스트를 추가하지 않고 대안과 확인 방법을 남긴다.

## 2. 확인 방법과 한계

수집기 파서, 공유 저장 계약, API 응답 변환, 저장소의 도착 및 위치 fixture, 2026-11-05 로컬 재생 자료를 읽었다. 공급자 응답 항목은 [경기버스정보 개발가이드의 버스도착정보목록조회](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrivalStation) 페이지에서 확인했다. 실서비스 API 키 호출, 운영 DB 및 SSH 접속은 하지 않았다.

도착 오퍼레이션은 `backend/business/arrivals/src/main/java/com/gustler/backend/arrivals/infrastructure/gbis/GbisArrivalSource.java:32`의 `/busarrivalservice/v2/getBusArrivalListv2`다. 같은 파일 48행에서 정류장 하나를 조회한다. 기준 주소는 `backend/worker-app/src/main/resources/application.yml:32`의 `https://apis.data.go.kr/6410000`이다. 공공데이터포털의 [경기도 버스도착정보 조회 서비스 페이지](https://www.data.go.kr/data/15080346/openapi.do)는 출력 항목을 comMsgHeader, msgHeader, msgBody 세 묶음으로만 보여 준다. 필드 단위 항목은 경기버스정보 개발가이드에 있다. 개발가이드 페이지는 서비스 버전을 따로 적지 않는다. 대신 실제 v2 응답(도착 fixture와 로컬 자료)의 행 키 28개와 `stateCd`, `stationId`가 모두 개발가이드 항목에 있다.

근거 파일의 약칭은 다음과 같다. 줄 번호는 이 작업 시점 기준이다.

| 약칭 | 파일 |
|---|---|
| 도착 파서 | `backend/business/arrivals/src/main/java/com/gustler/backend/arrivals/infrastructure/gbis/GbisArrivalSource.java` |
| 저장 계약 | `backend/common/src/main/java/com/gustler/backend/arrival/contract/ArrivalSnapshot.java` |
| 도착 fixture | `backend/business/arrivals/src/test/resources/gbis-arrivals-station.json` |
| 로컬 자료 | `dev/data/station-arrivals-20261105.json` |
| 위치 DTO | `backend/integrations/gbis-client/src/main/java/com/gustler/backend/gbis/api/dto/BusLocationResponse.java` |
| 위치 fixture | `backend/integrations/gbis-client/src/test/resources/gbis/location-live-snapshot.json` |

## 3. 원문 필드와 저장 여부

| 원문 필드 | 파서가 읽는가 | 저장 위치 | 운행 식별 또는 정보 시각으로 쓸 수 있는가 | 근거 |
|---|---|---|---|---|
| `msgHeader.queryTime` | 읽음 | `sourceQueryTime` 문자열 | 개발가이드는 요청 처리 시각이라고 적는다. 예측을 만든 정보 시각이 아니다. 시간대는 명세에 없다. 값이 같아도 같은 정보라는 보장은 없다 | 도착 파서:94, 저장 계약:19, 도착 fixture:5 |
| `vehId1/2` | 읽음 | `Prediction.vehicleId` | 물리 차량만 식별한다. 회차와 종점 재출발 같은 운행 경계를 구분하지 못한다 | 도착 파서:130, 147, 저장 계약:70, 도착 fixture:35 |
| `turnSeq` | 버림 | 없음 | 개발가이드는 노선의 회차지 순번이라고 적는다. 개별 운행 식별자가 아니며 대안 B의 보조 재료다 | 도착 fixture:34, 도착 파서:122, `backend/integrations/gbis-client/src/main/java/com/gustler/backend/gbis/api/dto/BusRouteStationResponse.java:53` |
| `stationNm1/2` | 버림 | 없음 | 개발가이드는 차량이 현재 있는 정류장 이름이라고 적는다. 운행이나 시각 식별자가 아니다 | 도착 fixture:30, 도착 파서:122, 로컬 자료:1의 `stations.<id>.rows[]` |
| `plateNo1/2` | 버림 | 없음 | 차량 번호판으로 물리 차량 식별이다. 운행과 정보 시각을 주지 않으며 이 목적의 추가 저장 이득이 없다 | 로컬 자료:1의 `stations.<id>.rows[]`, 도착 파서:122, 위치 DTO:40 |
| `stateCd1/2` | 버림 | 없음 | 차량 상태 코드다. 운행 식별자나 예측 생성 시각이 아니다. 도착 fixture에는 있지만 로컬 자료에는 없다 | 도착 fixture:39, 40, 도착 파서:122, 위치 DTO:61 |
| `locationNo1/2` | 읽음 | `Prediction.remainingStops` | 남은 정류장 수다. 운행이나 정보 시각 식별자가 아니다 | 도착 파서:148, 저장 계약:71, 도착 fixture:15 |
| 운행 식별자 | 해당 필드 확인 안 됨 | 없음 | 확인한 원문 행, 위치 DTO, 개발가이드 응답 항목 어디에도 없다 | 도착 파서:122, 저장 계약:70, 도착 fixture:11, 로컬 자료:1, 위치 DTO:39 |
| 차량별 또는 예측별 생성 시각 | 해당 필드 확인 안 됨 | 없음 | 개발가이드 응답 항목에도 요청 처리 시각인 `queryTime` 말고는 시각 항목이 없다 | 도착 fixture:5, 로컬 자료:1, 위치 DTO:25, 39 |

파서는 `stationId`, `staOrder`, `routeTypeCd`, `vehId`, `predictTimeSec`, `predictTime`, `remainSeatCnt`, `crowded`, `taglessCd`, `locationNo`, `lowPlate`, `routeId`, `routeName`, `routeDestId`, `routeDestName`, `flag`를 읽는다(도착 파서:122부터 152). 버리는 필드 중 운행 식별자나 예측 생성 시각으로 쓸 근거가 있는 필드는 확인하지 못했다.

저장 계약 16행의 `receivedAt`은 수신 시각이고 17, 18행의 `refreshAfterAt`, `staleAt`은 갱신 및 만료 시각이다. 이들은 예측 생성 시각이 아니다. `backend/api-app/src/main/java/com/gustler/backend/api/arrival/dto/StationArrivalsResponse.java:62`는 `sourceObservedAt`에 null을 넣는다. 예상 도착은 `backend/api-app/src/main/java/com/gustler/backend/api/arrival/domain/ArrivalPrediction.java:13`부터 17행처럼 수신 시각에 남은 초를 더한다.

위치 오퍼레이션은 `backend/integrations/gbis-client/src/main/java/com/gustler/backend/gbis/api/GbisLocationSource.java:29`의 `/buslocationservice/v2/getBusLocationListv2`다. 노선 단위로 조회하며 위치 DTO 28, 43, 58, 61행에 `queryTime`, `vehId`, `stationSeq`, `stateCd`가 있다. 위치 fixture의 차량 행 키는 `crowded`, `lowPlate`, `plateNo`, `remainSeatCnt`, `routeId`, `routeTypeCd`, `stateCd`, `stationId`, `stationSeq`, `taglessCd`, `vehId`다. ETA와 운행 식별자는 확인하지 못했다.

## 4. 로컬 자료 집계

| 항목 | 실행 결과 |
|---|---|
| 정류장 | 82곳 |
| `receivedAt` 범위 | 2026-11-05 14:01:46.276000부터 16:07:27.620000까지 |
| 로컬 자료의 행 키 합집합 | 28개 |
| 도착 fixture에만 있는 키 | `stateCd1`, `stateCd2`, `stationId` |
| 로컬 자료에만 있는 키 | `plateNo1`, `plateNo2` |
| 차량 예측 | 1,089건 |
| 빈 `vehId` | 0건 |
| 분 단위만 있는 예측 | 1건 |
| 초 예측이 있는 노선과 차량 쌍 | 897개 |
| 위 쌍 중 두 정류장 이상에서 관측된 쌍 | 156개 |
| 같은 노선과 차량의 정류장 순번으로 정렬한 인접 관측 쌍 | 191개 |
| 위 쌍 중 `receivedAt`이 같은 쌍 | 0개 |
| 뒤 정류장의 예측 도착 절대시각이 더 이른 쌍 | 2개 |
| 인접 관측 쌍 191개의 수신 간격 중앙값 | 11.714초 |
| 인접 관측 쌍 191개의 수신 간격 최댓값 | 7,522.504초 |

로컬 자료의 행 키는 다음과 같다. 원자료 행, 차량 ID, 차량 번호는 옮기지 않는다.

```text
crowded1 crowded2 flag locationNo1 locationNo2 lowPlate1 lowPlate2
plateNo1 plateNo2 predictTime1 predictTime2 predictTimeSec1 predictTimeSec2
remainSeatCnt1 remainSeatCnt2 routeDestId routeDestName routeId routeName
routeTypeCd staOrder stationNm1 stationNm2 taglessCd1 taglessCd2 turnSeq vehId1 vehId2
```

이 자료는 정류장마다 한 번씩 순서대로 조회한 약 두 시간의 로컬 재생용 자료다. 운영 분포를 대표하지 않는다. 수신 시각이 같은 비교 쌍이 없다는 것은 이 자료의 결과이며, 서로 다른 정류장의 조회 시각이 항상 다르다는 뜻은 아니다. 값이 우연히 같아도 같은 정보에서 나온 예측이라는 근거는 되지 않는다.

빈 차량 ID 0건도 이 자료에서만의 값이다. 저장 계약 70행과 도착 파서 155행의 `hasNoVehicleData`는 차량 ID가 없어도 ETA가 있는 예측을 허용한다. 두 정류장 이상에서 보인 쌍 수는 아래 명령의 `len(v) > 1`로 센 관측 수 기준이며, 같은 정류장 순번의 쌍은 인접 쌍 집계에서 제외한다. 분 단위만 있는 예측은 초 예측의 노선과 차량 집계에서 제외한다.

로컬 자료의 `receivedAt`이 수집한 쪽의 수신 시각인지 공급자의 `queryTime`을 옮긴 것인지는 파일만으로 확정하지 못한다. `dev/wiremock-extension/src/main/java/com/gustler/localgbis/StationReplay.java:132`의 `RecordedStation`은 이를 문자열로 받으며 의미를 검증하지 않는다. 도착 fixture와 로컬 자료 사이에 `stateCd`가 다른 이유도 공급자 변경인지 수집 과정 차이인지 확인하지 못했다.

집계를 다시 만드는 명령은 부록에 있다.

## 5. 대안 검토

### 대안 A. queryTime을 informationAt으로 사용

필요한 것은 서로 다른 정류장의 두 예측이 같은 정보에서 나왔다는 근거다. 현재 `queryTime`은 조회 응답 머리에 있고 이미 저장하지만 예측 생성 시각이라는 근거는 없다. 설계 의견서 SALMON-MATH-eta-chain-opinion-v1의 C.1은 조회 시각의 일치보다 같은 정보에서 나온 예측을 요구하고, C.2는 다른 시각 예측의 차이에 갱신 오차가 들어감을 설명한다. 같은 `queryTime`이라는 사실만으로 이 조건을 충족하지 못한다.

결론: 채택하지 않는다. 수신 시각보다 공급자 쪽 시각이라는 차이는 있지만 sameRun을 열지 못한다.

### 대안 B. 차량 ID와 예측 연속성으로 운행 추정

같은 노선과 `vehId`, 같은 회차 구간(`staOrder`와 `turnSeq`의 앞뒤), 정류장 순서와 일치하는 예측 도착 순서로 같은 운행인지 추정하는 안이다. 필요한 것은 이 규칙의 오식별 비율에 관한 근거다. 로컬 자료 한 번으로는 회차와 재출발 경계를 검증하지 못한다. 운영 연속 스냅샷과 위치 궤적의 `stationSeq`를 대조해야 한다. 설계 의견서 C.4처럼 오식별은 결측으로 드러나지 않고 틀린 구간시간을 만든다.

결론: 지금은 채택하지 않는다. 규칙과 검증 방법만 후속 후보로 남긴다. tripId 자리에 추정값을 넣으려면 그 검증이 먼저다.

### 대안 C. 수신 시각 차이의 예측 갱신 오차 상계로 align 사용

필요한 것은 같은 정류장과 차량의 연속 스냅샷에서 예측 도착 절대시각이 얼마나 변하는지 측정한 자료와 상계 값에 대한 사람의 결정이다. 11-05 자료는 정류장마다 한 번만 조회해서 이를 측정하지 못한다. 기존 구현 기록에는 11-08 운영 추출이 SSH 시간 초과로 실패했다고 적혀 있다. 제품 상계 값은 만들지 않으며 SAL-389에서 사람이 정하는 범주로 남긴다.

결론: 지금은 채택하지 않는다. 측정 방법만 남기고 상계 값은 정하지 않는다.

### 대안 D. 노선 단위 위치 응답 사용

필요한 것은 정류장 도착 예측이다. 위치 조회는 응답 머리에 같은 `queryTime`을 가진 차량 위치를 제공하지만 확인한 DTO와 fixture에 ETA가 없다.

결론: EtaChain.Observation의 대체 재료가 아니다. 대안 B의 운행 경계 검증 재료로만 쓸 수 있다.

### 대안 E. 공급자 명세 직접 확인

[경기버스정보 개발가이드의 버스도착정보목록조회](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBusArrivalStation) 응답 항목을 확인했다. 운행 식별자와 예측 생성 시각 항목이 없고, `queryTime`은 요청 처리 시각이다. 실제 v2 응답의 행 키는 모두 이 항목 목록에 있다.

결론: 명세로도 필드가 없음을 확인했다. 공급자가 응답 항목을 바꾸면 이 문서를 갱신하고 저장 쪽 작업을 새로 연다.

## 6. 결론과 EtaChain에 미치는 영향

`EtaChain.Observation` 변환을 만들지 않는다. 아래 salmon 패키지 경로는 PR #267(SAL-378)에 있고 이 문서를 쓴 시점의 dev에는 아직 없다. `backend/api-app/src/main/java/com/gustler/backend/api/salmon/domain/EtaChain.java:23`의 `Run`은 비어 있지 않은 `vehicleId`와 `tripId`가 필수이고, 40행의 `Observation`은 정보 시각이 필수다. 99행의 `sameRun`은 정보 시각이 다르면 `INFORMATION_MISMATCH`를 반환한다. 정렬에는 예측 갱신 오차 상계와 그 근거도 필요하다.

현재 도착 소스에는 운행 식별 근거, 동일 정보 시각의 근거, 예측 갱신 오차 상계가 없다. 따라서 이 소스에서 재탑승 구간시간을 EtaChain의 `Known`으로 만드는 경로를 추가하지 않는다. 대응되는 결측 사유는 `UNIDENTIFIED_VEHICLE`, `INFORMATION_TIME_MISMATCH`, `NO_ERROR_BOUND`다(`backend/api-app/src/main/java/com/gustler/backend/api/salmon/application/EtaFields.java:40`부터 45). 지금 이 소스가 실제로 세 사유를 반환하는 변환까지 구현되어 있다는 뜻은 아니다.

이는 PR #267의 구현 기록(`docs/salmon-action/implementation-20261108.md`)에 적은 입력 부족 결론을 원문 자료와 파서로 재확인한 것이다. `ArrivalSnapshot`, `RouteArrival`, `Prediction`, `NORMALIZATION_VERSION`, JSONB payload, `ArrivalStopSource`와 테스트 기준 수는 변경하지 않는다. ETA에서 탑승 성공 확률을 만들지 않는다.

## 7. 확인하지 못한 것과 확인 방법

| 미확인 항목 | 확인 방법 |
|---|---|
| `queryTime`의 시간대 | 개발가이드에 시간대가 없다. 공급자 답변이나 실제 응답과 수신 시각 대조로 확인한다. 시간대를 임의로 붙이지 않는다 |
| 운영 스냅샷의 빈 차량 ID 비율, 회차 및 재출발 오식별 | 승인된 읽기 전용 추출과 위치 궤적 대조로 대안 B를 검증한다 |
| 같은 정류장과 차량의 예측 갱신 변화 | 연속 스냅샷의 `receivedAt + sourceRemainingSeconds` 변화를 수신 간격별로 집계한다. 분 해상도 예측은 양자화 오차를 구분한다 |
| 도착 fixture와 로컬 자료 사이의 `stateCd` 차이 | 두 자료의 수집 과정 및 시점별 명세를 대조한다 |
| 로컬 자료의 `receivedAt` 출처 | 해당 자료 생성 절차에서 수신 시각인지 공급자 시각을 옮긴 것인지 확인한다 |

기존 검사기는 PR #267의 `docs/salmon-action/scripts/check_arrival_assumptions.py`다. 기존 구현 기록이 참조하는 추출기는 `eta_extract_arrivals.sh`이지만 이 워크트리에서 파일 경로를 찾지 못했다. 운영 추출 전에 관리 위치와 읽기 전용 동작을 확인해야 한다. 검사기는 빈 차량 ID와 해상도 등을 집계하지만 운행 경계나 갱신 오차 상계의 근거를 자동으로 확정하지 않는다. 이번에는 운영 추출과 접속을 하지 않았다.

## 8. 후속 후보

1. 운영 연속 스냅샷과 위치 궤적으로 회차 및 재출발 경계에서 대안 B의 오식별을 검증한다.
2. 같은 정류장의 연속 스냅샷으로 대안 C의 예측 갱신 변화를 측정하고, 사용할 상계 값은 SAL-389에서 사람이 결정한다.

후속 티켓 생성과 우선순위는 사람이 정한다. 운행 식별 추정, 상계 값 도입, 버리는 필드의 추가 저장은 이 작업에서 구현하지 않는다.

## 부록. 4절 집계 재현 명령

저장소 루트에서 실행한다. 종료 코드는 0이다.

```bash
python3 -I - dev/data/station-arrivals-20261105.json backend/business/arrivals/src/test/resources/gbis-arrivals-station.json <<'EOF'
import json, sys, collections, datetime as dt
capture, fixture = sys.argv[1], sys.argv[2]
data = json.load(open(capture))
fmt = '%Y-%m-%d %H:%M:%S.%f'
keys = set(); vehicles = 0; empty_vehicle = 0; minute_only = 0; times = []
visits = collections.defaultdict(list)
for station in data['stations'].values():
    received = dt.datetime.strptime(station['receivedAt'], fmt); times.append(received)
    for row in station['rows']:
        keys.update(row)
        for ordinal in (1, 2):
            seconds = row.get(f'predictTimeSec{ordinal}'); minutes = row.get(f'predictTime{ordinal}')
            vehicle = row.get(f'vehId{ordinal}')
            if seconds in ('', None) and minutes in ('', None) and vehicle in ('', None):
                continue
            vehicles += 1
            if vehicle in ('', None):
                empty_vehicle += 1; continue
            if seconds in ('', None):
                minute_only += 1; continue
            visits[(row['routeId'], vehicle)].append((row['staOrder'], received + dt.timedelta(seconds=seconds), received))
pairs = conflicts = same_time = 0; gaps = []
for observed in visits.values():
    ordered = sorted(observed)
    for first, second in zip(ordered, ordered[1:]):
        if first[0] == second[0]:
            continue
        pairs += 1
        gaps.append(abs((second[2] - first[2]).total_seconds()))
        same_time += first[2] == second[2]
        conflicts += second[1] < first[1]
gaps.sort()
fixture_keys = set()
for row in json.load(open(fixture))['response']['msgBody']['busArrivalList']:
    fixture_keys.update(row)
print('stations', len(data['stations']), 'receivedAt range', min(times), max(times))
print('row keys', sorted(keys))
print('fixture only keys', sorted(fixture_keys - keys), 'capture only keys', sorted(keys - fixture_keys))
print('vehicles', vehicles, 'empty vehId', empty_vehicle, 'minute only', minute_only)
print('route-vehicle keys', len(visits), 'seen at 2+ stops', sum(len(v) > 1 for v in visits.values()))
print('adjacent stop pairs', pairs, 'same receivedAt', same_time, 'later stop arrives earlier', conflicts)
print('receivedAt gap seconds median', gaps[len(gaps) // 2] if gaps else None, 'max', gaps[-1] if gaps else None)
EOF
```
