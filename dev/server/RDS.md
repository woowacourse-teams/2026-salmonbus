# 공유 dev의 RDS 연결과 배포

공유 dev의 API와 worker는 전용 RDS `salmonbus-dev-db`를 사용한다.
개인 로컬 개발은 기존 Docker PostgreSQL 구성을 사용한다. MongoDB와 GBIS WireMock은
공유 dev EC2에 유지하며, 운영 DB는 전환 대상이 아니다.

## 연결 검증

서버의 `/etc/salmonbus-dev/database-target.json`은 root 소유, 권한 0600으로 둔다.
환경은 dev, DB 식별자는 `salmonbus-dev-db`, ARN은 dev EC2 marker와 같은 계정과
서울 리전이어야 한다. endpoint와 port는 AWS에서 확인한 값만 기록한다.
`certificateSha256`은 함께 설치한 `rds-ca.pem`의 SHA256이다. CA는 root 소유 0644로
설치해 앱이 읽을 수 있게 하고, endpoint·ARN·인증정보는 저장소에 넣지 않는다.

기동 검증은 대상 파일이 없으면 기존 로컬 DB만 허용한다. 파일이 있으면 API와 worker
모두 검증된 dev RDS URL을 사용해야 한다. TLS는 `verify-full`로 인증서와 호스트명을
검증한다. 앱에는 기존 dev 전용 계정을 사용하며 마스터 계정을 설정하지 않는다.
systemd는 환경 파일의 DB 설정을 사용한다. worker의 GBIS loopback 고정과 실제 키 거부는
유지한다. basis 교체 시 RDS 대상 파일·CA·앱 계정·Origin 설정을 보존한다.

## PR 머지 후 자동 배포

기존 dev CodePipeline의 API → worker 순서를 사용한다. 별도 배포 단계나 IAM 권한을
추가하지 않는다. API 배포 번들에 같은 빌드의 basis를 포함하고 manifest로 함께 검증한다.

1. API BeforeInstall에서 dev 배포 그룹·EC2 marker·번들 무결성을 검증한다.
2. basis 계약이 같으면 기반 설정 교체를 생략하고 기존 앱 배포를 진행한다.
3. 계약이 바뀌면 앱 설정·unit·수동 전환용 drop-in·앱 버전 링크를 보호된 경로에
   백업하고 API와 worker를 정지한다. 기존 데이터를 보존하는 basis 교체가 끝나면
   CodeDeploy의 앱 설치·기동·health 검증을 이어서 수행한다.
4. 각 앱의 ValidateService가 성공하면 이번 RDS 수동 전환용 drop-in을 제거하고
   저장소의 unit을 사용한다. 다른 drop-in은 수정하지 않는다.
5. 기반 교체나 이후 hook이 실패하면 기록된 이전 설정·basis·앱 버전 링크를 복구하고
   원래 실행 중이던 서비스를 기동한다. 복구도 실패하면 수동 확인이 필요한 오류로 남긴다.

기반 교체가 필요한 배포에는 공유 dev 중단 시간이 발생한다. 데이터 계약이 바뀌는 변경은
기존 검증이 거부하므로 별도 이전 계획이 필요하다. 이 배포 경로는 DB를 초기화하거나
기존 데이터를 다시 복사하지 않는다. 새 Flyway migration은 기존 앱 기동 경로를 따르며,
배포 설정 원복이 DB schema나 새 데이터를 되돌리지는 않는다.

## 2026-10-15 데이터 전환 기록

API와 worker를 정지한 상태에서 로컬 PostgreSQL의 최종 백업을 만들고 dev RDS의
앱 전용 DB에 복원했다. 38개 테이블 21,606,403건, Flyway 이력과 sequence 상태를
대조한 뒤 두 앱의 DB 설정을 함께 전환했다. 실제 TLS 연결과 worker의 새 데이터 저장을
확인했다. 비밀과 원본 데이터는 서버의 보호된 백업 경로에만 보관한다.

현재 앱 JAR와 배포 버전 링크는 바꾸지 않고 별도 기동 검증 도구와 systemd drop-in으로
RDS 연결을 적용했다. 위 자동 배포 경로가 반영되면 앱별 검증 성공 후 해당 drop-in을
제거한다. 기존 PostgreSQL 컨테이너와 데이터는 원복용으로 보존한다.

## DB 원복과 worker 부하

DB 원복이 필요하면 앱을 정지하고 전환 전 설정에 맞는 unit·도구·basis/app 조합을
복구한다. RDS에서 전환 후 저장한 데이터는 보존하며, 로컬 DB로 자동 병합되지 않는다.
RDS를 지원하지 않는 옛 basis로 되돌리기 전에 DB 설정과 기동 검증의 호환성을 확인한다.

worker 수집 주기·수집 노선·차량 규모 조정은 DB 전환과 별도로 검증한다. 현재 WireMock
재생은 방향당 차량 수 상한이 6대다. RDS 사양을 맞춘 것만으로 운영과 같은 부하나
성능을 재현했다고 판단하지 않는다. 사용자 API·채팅 부하 시험은 이 작업에 포함하지 않는다.
