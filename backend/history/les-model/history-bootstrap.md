# 원관측 이력으로 레스 체크포인트 준비하기

`les-history-bootstrap`은 지정한 DB에서 관측을 읽어 체크포인트 파일을 만든다. 모델을 활성화하거나 과거 예보를 발행하지 않는다. PostgreSQL 연결마다 읽기 전용 세션을 설정하므로 DB 쓰기는 거부된다. 파일 저장은 `--checkpoint-directory`에서만 이루어진다.

설정 형식은 기존 유지보수 명령과 같다. `maintenance-app/config/quality.properties.example`을 참고한다. `LOCAL`은 loopback DB만 허용한다. DB URL·계정·암호는 설정이 가리키는 비공개 환경 파일에서 읽고 명령 인자로 전달하지 않는다. 실패 출력에는 오류 코드만 포함한다.

아래는 `backend` 디렉터리에서 사용하는 명령 예시다. 숫자와 절대 경로를 실제로 검증한 자료의 값으로 바꿔야 한다. 이 명령을 운영 DB에 실행하지 않았다.

```sh
nice -n 10 java -XX:ActiveProcessorCount=1 -Xmx512m \
  -jar maintenance-app/build/libs/maintenance-app-0.0.1-SNAPSHOT.jar \
  les-history-bootstrap \
  --config /absolute/path/maintenance.properties \
  --directory /absolute/path/java-inference-bundle-v2 \
  --route-version 123 \
  --source-origin-batch 1000 \
  --through-batch 2000 \
  --checkpoint-directory /absolute/path/les-checkpoints \
  --batch-limit 100 \
  --maximum-history-rows 250000
```

| 옵션 | 뜻 |
| --- | --- |
| `--config` | 기존 유지보수 설정 파일. `target.kind`, `database.env-file` 사용 |
| `--directory` | 해시와 입력 계약이 맞는 레스 번들. 통계 조회 목적이므로 `serving_ready=false`도 허용 |
| `--route-version` | 체크포인트를 사용할 DB 노선 판본 ID |
| `--source-origin-batch` | 전체 원자료의 첫 배치임을 확인한 ID. 처음 준비할 때 실제 DB 원점과 대조 |
| `--through-batch` | 포함할 마지막 배치 ID. 이 배치 이후 관측은 입력에 사용하지 않음 |
| `--checkpoint-directory` | 체크포인트 출력 디렉터리. 모델 번들 디렉터리와 분리 |
| `--batch-limit` | 한 번에 전진할 배치 수, 1~1000 |
| `--maximum-history-rows` | 각 단계에서 읽을 원관측 수의 상한. 넘으면 일부만 잘라 계산하지 않고 중단 |

모든 옵션이 필요하다. 알 수 없는 옵션, 0·음수 식별자, 잘못된 숫자는 DB를 열기 전에 거부한다. 고정 통계와 DB 정류장 목록이 다르거나, 원관측이 누락됐거나, 체크포인트의 근거 시각이 맞지 않으면 작업을 중단한다.

완료 결과에는 `databaseReadOnly=true`, `activated=false`, `forecastsPublished=false`, 처리한 체크포인트 수와 번들 해시가 나온다. 체크포인트 수가 0이면 지정한 범위까지 이미 준비되어 추가로 전진하지 않았다는 뜻이다. 이는 모델 활성화나 전체 원본 입력 일치 검증이 끝났다는 뜻은 아니다.

원관측을 확인한 순서대로 작은 범위씩 준비한다. 파일 저장은 체크섬과 내용이 함께 교체되며 기존 완료 지점에서 다시 실행할 수 있다. `batch-limit`을 작게 하면 한 단계에서 추가로 읽는 자료는 줄지만 전체 실행의 CPU·메모리 상한을 보장하지 않는다. 실제 사용량을 확인하면서 실행한다. Worker에서 같은 디렉터리를 사용할 때는 준비 작업을 먼저 마친다.

준비된 디렉터리는 Worker의 `forecast.les.history-seed-directory`에 연결한다. 이 설정만으로 레스 모델이 활성화되지는 않는다. 서빙 번들의 검증·활성화 절차는 별도다.
