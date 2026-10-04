"""로컬 JSON만 생성한다. Grafana 조회/수정은 하지 않는다."""
import json
from pathlib import Path

prom = {'type': 'prometheus', 'uid': '${DS_PROMETHEUS}'}
loki = {'type': 'loki', 'uid': '${DS_LOKI}'}
panels = []
def add(title, expr, source=prom, kind='timeseries', unit='short', description='', instant=False, legend='{{operation}} {{outcome}}'):
    i = len(panels)
    panel = {'id': i+1, 'title': title, 'type': kind, 'description': description,
        'gridPos': {'x': (i % 2)*12, 'y': (i//2)*9, 'w': 12, 'h': 9},
        'datasource': source, 'targets': [{'refId': 'A', 'expr': expr, 'datasource': source,
            'instant': instant, 'range': not instant, 'legendFormat': legend}],
        'fieldConfig': {'defaults': {'unit': unit}, 'overrides': []},
        'options': {'legend': {'displayMode': 'table', 'placement': 'bottom'}, 'tooltip': {'mode': 'multi'}}}
    if source == loki:
        panel['targets'][0].update(queryType='instant' if instant else 'range', maxLines=100, direction='backward')
    if kind == 'table':
        panel['targets'][0]['format'] = 'table'
        panel['options'] = {'showHeader': True}
        panel['transformations'] = [{'id':'labelsToFields','options':{'mode':'columns'}}]
        for field, label in [('routeName','노선 번호'),('stopName','정류장 이름'),('stopId','정류장 번호'),('direction','방향'),('modelDeploymentId','모델'),('distance','예측 거리')]:
            panel['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':field},'properties':[{'id':'displayName','value':label}]})
        # 내부 키는 쿼리 그룹에 유지하지만 사용자 표에는 이름·방향·정류장 번호만 보인다.
        for field in ['routeId', 'routeVersionId', 'stopOrder', 'Time']:
            panel['fieldConfig']['overrides'].append({'matcher': {'id':'byName','options':field},
                'properties':[{'id':'custom.hidden','value':True}]})
    if kind == 'logs': panel['options'] = {'showTime':True,'wrapLogMessage':True,'sortOrder':'Descending','enableLogDetails':True}
    panels.append(panel)

sel='{job="salmonbus/worker",route_name=~"${route:raw}"}'
for title, metric, desc in [
    ('노선별 마지막 배치 커밋 이후', 'commit', '빈 예보도 포함한 배치 완료. 재시작 후 첫 커밋까지 알 수 없음. 야간 운행 중단과 장애를 구분한다.'),
    ('노선별 실제 예보 생성 이후', 'nonempty', '예보가 1행 이상인 커밋만 포함한다. 배치 완료와 구분한다.'),
    ('노선별 최근 예보 입력 관측의 나이', 'input', '현재 시각 - 마지막 비어 있지 않은 예보에 사용한 관측 시각. 개별 정류장 신선도 보장은 아님.')]:
    add(title, f'time() - salmonbus_forecast_last_{metric}_timestamp{sel}', unit='s', description=desc, legend='{{route_name}}번')
add('worker 작업별 p95', 'histogram_quantile(0.95, sum by (le, operation) (rate(salmonbus_worker_operation_seconds_bucket{job="salmonbus/worker"}[$__rate_interval])))',unit='s',description='계측된 전체 호출의 근사 p95. 중첩 작업 시간을 서로 더하지 않는다. returned는 커밋 성공을 뜻하지 않는다.')
add('worker 작업별 평균', 'sum by (operation) (rate(salmonbus_worker_operation_seconds_sum{job="salmonbus/worker"}[$__rate_interval])) / sum by (operation) (rate(salmonbus_worker_operation_seconds_count{job="salmonbus/worker"}[$__rate_interval]))', unit='s')
add('수집 대상 상태', 'up{job=~"salmonbus/(api|worker)"}',description='0은 scrape 실패. 시계열 자체 소실은 별도 absent 알림 필요.',legend='{{job}}')
add('worker 스케줄러 heartbeat 경과', 'time() - salmonbus_monitoring_heartbeat_timestamp{job="salmonbus/worker"}',unit='s',description='60초 주기. 증가하면 스케줄러 지연 또는 수집 문제를 함께 확인한다.')
add('집계 누락 표본 누적', 'salmonbus_accuracy_dropped_samples{job="salmonbus/worker"}',description='메모리 그룹 한도 초과/부가 데이터 누락. 재시작 시 0으로 초기화. 값 증가 시 정확도 해석을 중단한다.')

base='{unit="salmonbus-worker.service"} |= "event=forecast_accuracy " | logfmt | routeName=~"${route:raw}" | stopName=~"${stop:raw}" | distance=~"${distance:raw}" | modelDeploymentId=~"${model:raw}"'
group='routeId,routeVersionId,stopOrder,routeName,stopName,stopId,direction,modelDeploymentId,distance'
def total(field): return f'sum by ({group}) (sum_over_time({base} | unwrap {field} | __error__="" [$__range]))'
for title, numerator, denominator, unit, description in [
    ('정류장별 평균 좌석 오차 (MAE)', 'absoluteErrorSum','maeCount','short','0에 가까울수록 좌석 오차가 작다. 유효 expected_seats가 있는 평가만 포함. 완료 표본·누락을 함께 본다.'),
    ('정류장별 만석 확률 오차 (Brier)', 'brierSum','probabilityCount','short','표시된 만석 확률과 실제 만석 여부의 제곱 오차. 낮을수록 좋으며 순수한 확률 보정 지표는 아니다.'),
    ('정산 완료분 중 좌석 평가 가능 비율', 'maeCount','completed','percentunit','전체 발행 예보의 평가 커버리지가 아니다. Writer가 전달한 완료분만 분모이며 PENDING 및 별도 일괄 품질 제외는 포함하지 않는다.')]:
    add(title, f'{total(numerator)} / ({total(denominator)} > 0)',loki,'table',unit,description,True)
add('정류장별 좌석 평가 표본 수', total('maeCount'),loki,'table',description='선택 기간에 출력된 집계. 동일 차량의 반복 예보도 각 평가 단위로 센다.',instant=True)
add('정류장별 만석 확률 평가 표본 수',total('probabilityCount'),loki,'table',instant=True)
add('집계 원문 (노선·정류장 이름)', base,loki,'logs',description='5분마다 출력. 표본이 없으면 No data가 정상일 수 있다. 실제 도착시각이 아닌 집계 출력시각으로 검색된다.')
add('노선 이름이 붙은 worker 지연·실패', '{unit="salmonbus-worker.service"} |= "event=worker_operation " | logfmt | routeName=~"${route:raw}"',loki,'logs',description='기존 로그 필드를 보존하고 해석 가능한 작업에 routeName만 추가. 모든 노선 공통 작업과 오래된 버전은 이름이 없을 수 있다.')
add('worker 로그 heartbeat', 'sum(count_over_time({unit="salmonbus-worker.service"} |= "event=monitoring_heartbeat " [5m]))',loki,description='최근 5분. 없음은 0으로 강제하지 않는다. worker/Alloy/Loki 경로를 함께 점검한다.')

variables=[{'name':'DS_PROMETHEUS','type':'datasource','query':'prometheus','label':'Prometheus 데이터 소스'},
 {'name':'DS_LOKI','type':'datasource','query':'loki','label':'Loki 데이터 소스'}]
for name,label in [('route','노선 번호 (정규식)'),('stop','정류장 이름 (정규식)'),('distance','예측 거리: 1-3 / 4-6 / 7-12'),('model','모델 배포 ID')]:
 variables.append({'name':name,'label':label,'type':'textbox','query':'.*','current':{'text':'.*','value':'.*'}})
dashboard={'id':None,'uid':'salmonbus-forecast-quality-v1','title':'Salmonbus 추가 · 예보 품질과 수집 상태',
 'schemaVersion':39,'version':0,'tags':['salmonbus','additive-observability'], 'timezone':'Asia/Seoul',
 'refresh':'1m','time':{'from':'now-6h','to':'now'},'templating':{'list':variables},'annotations':{'list':[]},
 'panels':panels, 'description':'기존 대시보드 수정 없이 추가. 신규 배포 후 데이터만 관측. 정류장별 통계는 진단용 요약이며 장기 정확도 원장이 아니다.'}
Path(__file__).with_name('grafana-additions.json').write_text(json.dumps(dashboard,ensure_ascii=False,indent=2)+'\n')
