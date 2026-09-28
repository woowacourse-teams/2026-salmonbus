# 차량 좌석 미제공 사유 / 2026-09-28

상태: SAL-137 작업 브랜치 구현 / 운영 배포 전. 아래 JSON은 설명용 예시다.

## 요청

`GET /api/v1/routes/{routeId}/vehicles`

변경 필드: `vehicles[].seat.reason`. `observation.state=UNKNOWN`과는 별개인 차량별 좌석 상태다.

## 응답

좌석 수를 제공하는 응답은 그대로다. reason은 포함하지 않는다.

```json
{"kind":"EXACT","remaining":12}
```

좌석 수를 제공하지 않는 응답은 reason을 포함한다. remaining은 포함하지 않는다.

```json
{"kind":"UNKNOWN","reason":"QUALITY_WITHHELD"}
```

| reason | 의미 |
| --- | --- |
| NOT_REPORTED | 상류에서 좌석 값을 보내지 않음 |
| REPORTED_UNKNOWN | 상류에서 좌석 값을 모른다고 응답함(-1) |
| QUALITY_WITHHELD | 숫자로 보고된 좌석을 품질 판정 때문에 숨김 |

원본 좌석이 null/-1이면서 품질 조사도 진행 중이면 원래 미제공 사유가 우선이다. 정상 숫자가 있지만 70석 초과 / 제외 편도 / 편도 경계 미확정 / 조사 중이면 QUALITY_WITHHELD다. 위치가 유효한 차량은 위치를 계속 반환한다. 이번 변경으로 관측 원본을 고치거나 삭제하지 않는다.

정규화된 DB에서는 -1도 remaining_seats=null이고 seat_unknown_reason=REPORTED_UNKNOWN이다. API는 기존 사유 컬럼을 읽어 원래 누락과 구별한다. 데이터베이스 마이그레이션은 없다.

프론트는 배포 전후 호환을 위해 reason이 없거나 모르는 값이면 기존 좌석 정보 없음 표시를 유지할 수 있다. 이 필드는 미제공 이유만 알려주며 품질 조사의 상세 상태나 복구 예정 시각을 제공하지 않는다. HTTP 상태 / EXACT 응답 / board 응답은 바뀌지 않는다.
