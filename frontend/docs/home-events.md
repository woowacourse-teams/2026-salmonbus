# 홈 지도와 날씨

홈은 판교역에서 시작합니다. 날씨는 마지막으로 조회한 지역을 기준으로 표시하고, 새로고침을 누르면 현재 지도 중심의 날씨를 불러옵니다. 날씨표에는 현재 날씨와 앞으로 다섯 시간의 기온과 강수 확률을 표시합니다.

## 로컬 실행

1. `env/.env.development`에 네이버 지도 공개 식별자 `WEBPACK_NAVER_MAP_KEY_ID`와 `API_PROXY_TARGET`을 설정합니다.
2. `pnpm home:collect`로 운행 중인 지원 노선의 형상과 승차 가능 정류장을 수집합니다. 기본 수집 주소는 운영 API이며, `HOME_ROUTES_ORIGIN`을 환경변수나 `env/.env.home.local`에 지정해 바꿀 수 있습니다.
3. `pnpm dev`로 실행합니다. `pnpm home:watch`는 시작할 때와 이후 6시간마다 지도 데이터를 갱신합니다.

### 날씨 지역 이름

조회한 지도 중심이 들어가는 행정구역 이름을 표시합니다. 서울·경기·인천은 읍·면·동까지, 다른 지역은 시·군·구까지 표시합니다. 날씨 지역을 확인할 때 지도 중심이 속한 0.25도 격자 조각 하나만 불러옵니다. 행정구역을 찾을 수 없으면 가까운 정류장 이름이나 ‘지도 중심 날씨’를 표시합니다.

경계 원본은 [통계청 SGIS](https://sgis.mods.go.kr/view/pss/openDataIntrcn)의 자료(공공누리 제1유형)를 가공한 [VW LAB 행정동 경계 2026-10-01](https://github.com/vuski/admdongkor/tree/master/ver20261001)입니다. 가공 데이터는 [CC BY 4.0](https://github.com/vuski/admdongkor/blob/master/LICENSE-DATA)에 따라 사용하며, 이 앱은 표시 범위에 맞춰 경계를 합치고 단순화했습니다. 경계를 갱신하려면 원본 GeoJSON을 받아 `frontend`에서 아래 명령을 실행합니다. 마지막 명령은 `adminAreaTiles` 폴더의 `*.json`을 모두 지우고 0.25도 격자 조각으로 다시 만듭니다.

```sh
curl -L -o HangJeongDong_ver20261001.geojson \
  https://raw.githubusercontent.com/vuski/admdongkor/master/ver20261001/HangJeongDong_ver20261001.geojson
npm exec --yes --package mapshaper@0.7.101 -- mapshaper HangJeongDong_ver20261001.geojson \
  -each 'label = ["11","28","41"].includes(sido) ? adm_nm : sidonm + " " + sggnm' \
  -dissolve label -simplify dp interval=40m keep-shapes \
  -o format=geojson precision=0.00001 /tmp/salmonbus-admin-regions.geojson
python3 scripts/pack-home-areas.py src/pages/route-select/home/data/adminAreaTiles </tmp/salmonbus-admin-regions.geojson
```

## 배포와 갱신

수집기는 `public/home-events.json`에 지도 데이터를 씁니다. webpack과 배포는 기존 경로인 `assets/home-events.json`을 사용합니다. 수집에 실패하면 마지막으로 수집한 파일을 유지합니다.

프런트 배포와 별도 CodeBuild 갱신 작업은 같은 수집기를 사용합니다. 갱신 작업은 `frontend/buildspec.home-events.yml`을 사용하고, `S3_BASE`에 프런트 배포 경로를 지정합니다. `frontend/deploy/home-events-schedule.yml`로 설정된 EventBridge 일정은 이 작업을 6시간마다 실행합니다.

수집기의 좌표와 형상 선택은 `python3 scripts/collect-home-events.py --self-check`로 확인할 수 있습니다.

[Open-Meteo 날씨 문서](https://open-meteo.com/en/docs)
