# SAL-359 PR 그림

- `forecast-before-after.excalidraw`: 편집 가능한 원본. Excalidraw CLI로 생성했다.
- `forecast-before-after.png`: PR에 삽입하는 로컬 렌더링 결과. 1600×1200, 흑백.

상단은 같은 버스가 정류장에 도착해 하차와 승차를 마치는 순서다. 0 → 6 → 0석은 설명용 예시이며 실제 차량의 관측 결과가 아니다. 하차 후 좌석 수와 출발 만석을 서로 다른 시점의 목표로 표현한다.

하단은 기존 좌석 분포 모델과 새 결합 모델의 입력/출력/정산 흐름이다. 코드는 LesSeatPredictor, ForecastBatchWriter, JdbcLesFeatureQuery, JdbcLesForecastEvaluation, ConfirmedLesEvaluation을 기준으로 확인했다. 설명을 위해 품질 판정, 체크포인트와 예외 처리는 생략했다.

평가 수치는 `../20-route-training-result-20261105.md`의 20개 노선, 2026-11-02~04, 356,271건 비교다. 두 모델에 같은 입력과 관측 기반 추정 정답을 제공한 결과이며 실제 운영의 전후 성과가 아니다.

JSON의 ID, 텍스트 바인딩, 흑백, 컨테이너 포함, 텍스트 겹침을 검사했다. PNG 렌더링에서 글자/연결선/여백/잘림을 시각 검수했다. Obsidian 편집기와 모바일 화면에서의 검수는 수행하지 않았다.
