# 레스 정산 결과 한 건 복원

`les-evaluation-restore`는 보관된 정산 결과 한 건을 DB로 복원한다. 모델 활성화, 예보 재발행, 정산 재계산, 기존 관측 원자료 복원은 하지 않는다. 원자료 관측 FK 대신 보관 원본의 해시를 결과에 남긴다. 같은 결과가 이미 복원됐으면 `restoredRows=0`, 새로 복원되면 `1`이다. 값이 다른 기존 결과를 덮어쓰지 않는다.

## 입력 준비

복원할 결과의 키는 관측 ID, 목표 정류장 순번, 목표 종류, 라벨 판본, 품질 판본이다. `les_evaluation_archive`에서 이 키에 대응하는 `object_hash`를 확인한다. 목표 종류는 `AFTER_ALIGHTING_ESTIMATE` 또는 `DEPARTURE_ZERO`다. 좌석과 확률은 각각 별도 결과이므로 둘 다 필요하면 두 명령을 실행한다.

기존에 승인된 S3 조회 권한으로 다음 객체를 로컬 파일로 다운로드한다. 아래 명령은 운영자가 실행할 절차이며 이번 개발 중 실제 S3나 운영 DB에서 실행하지 않았다. `ARCHIVE_HASH`는 DB에서 확인한 64자리 SHA-256이다.

```sh
umask 077
aws s3 cp "s3://techcourse-project-2026/salmonbus-be/les-evaluation-archive/v1/${ARCHIVE_HASH}.json" /private/path/les-archive.json
chmod 600 /private/path/les-archive.json
```

파일은 비어 있지 않은 일반 파일, 소유자 전용 권한, 최대 16 MiB여야 한다. 심볼릭 링크는 거부한다. 명령은 파일 바이트를 승인 전에 한 번 읽는다. SHA-256이 DB에 기록된 원본과 같아야 하며 내부 JSON의 결과 키도 같아야 한다. 파일 이름이나 사용자가 제시한 해시만 믿지 않는다. 원격 업로드와 결과 삭제 기능은 이 명령에 연결하지 않았다.

## 실행

기존 maintenance 설정 파일을 사용한다. 설정에는 `target.kind=LOCAL|ACADEMY`, `database.env-file=...`가 필요하다. 비밀 파일 접근 조건과 ACADEMY의 `/etc/salmonbus/worker.env` 제한은 기존 품질 정비 명령과 같다.

```sh
/path/to/maintenance-app/bin/maintenance-app les-evaluation-restore \
  --config /private/path/maintenance.properties \
  --archive-file /private/path/les-archive.json \
  --observation-id 12345 --target-stop-order 7 \
  --target-kind DEPARTURE_ZERO --label-version les-identity-safe-v1 \
  --quality-revision 0
```

숫자는 명령 형식을 보여 주는 예시이며 실제 결과 키가 아니다. ACADEMY DB에는 추가로 `--approval /private/path/approval.json`이 필수다. 기존 승인 형식 `salmonbus-migration-approval-v1`을 사용하며 action은 `ACADEMY_LES_EVALUATION_RESTORE`, artifactSha256은 다운로드한 파일의 실제 해시, databaseIdentitySha256은 대상 DB identity 해시여야 한다. 만료 시각도 검사한다. 다른 명령의 승인을 재사용할 수 없다. LOCAL은 이 승인 파일을 요구하지 않는다.

성공 출력은 `status=succeeded`와 `restoredRows`, `activated=false`, `forecastsPublished=false`다. 실패 시 비밀 값이나 예외 원문 대신 안정된 오류 코드만 출력한다. 복원 과정은 트랜잭션으로 결과와 보관 상태를 함께 바꾼다. 동일 키에 다른 해시가 등록되거나 기존 결과 값이 다르면 복원하지 않는다.

## 검증 범위

명령 테스트는 옵션 검증, 정확한 키 전달, 승인 action/파일/DB 범위, 파일 교체와 잘못된 해시 거부, 파일 크기 제한, 업로드 차단, 모델·스케줄러 미기동을 확인한다. 실제 PostgreSQL의 정산→보관→삭제→복원 경로는 `LesPublicationLifecycleTest`에서 검사한다. 실제 운영 S3 권한과 네트워크, 실제 운영 DB 복원은 별도 확인 대상이다.
