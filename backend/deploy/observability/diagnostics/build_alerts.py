#!/usr/bin/env python3
"""Generate PAUSED Grafana MCP rule payloads. Does not send notifications."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parent
rules=[]

def rule(uid,title,expr,threshold,pending,summary,action,severity='critical'):
    rules.append({'operation':'create','rule_uid':uid,'org_id':1,'folder_uid':'salmonbus-alerts',
        'rule_group':'salmonbus-diagnostics-prepared','title':title,'condition':'B','for':pending,
        'is_paused':True,'no_data_state':'OK','exec_err_state':'Alerting',
        'labels':{'service':'salmonbus','severity':severity,'prepared_by':'SAL-158'},
        'annotations':{'summary':summary,'description':action,
                       'runbook_url':'https://higgs95.grafana.net/d/salmonbus-db-diagnostics',
                       '__dashboardUid__':'salmonbus-db-diagnostics','__panelId__':'2'},
        'notification_settings':{'receiver':'salmonbus-service-discord','groupBy':['service'],
            'groupWait':'30s','groupInterval':'5m','repeatInterval':'1h'},
        'data':[{'refId':'A','datasourceUid':'grafanacloud-prom','relativeTimeRange':{'from':600,'to':0},
                 'model':{'refId':'A','expr':expr,'instant':True,'range':False,'intervalMs':60000,'maxDataPoints':43200}},
                {'refId':'B','datasourceUid':'__expr__','relativeTimeRange':{'from':0,'to':0},
                 'model':{'refId':'B','type':'threshold','expression':'A',
                    'conditions':[{'type':'query','query':{'params':['A']},'reducer':{'type':'last','params':[]},
                                   'evaluator':{'type':'gt','params':[threshold]},'operator':{'type':'and'}}]}}]})

http='http_server_requests_seconds_count{job="salmonbus/api",uri=~"/api/v1/.*"}'
errors='http_server_requests_seconds_count{job="salmonbus/api",uri=~"/api/v1/.*",status=~"5.."}'
rule('salmonbus-diag-api-5xx','API 조회 실패 반복',f'(sum(increase({errors}[2m])) or (0 * sum(increase({http}[2m]))))',2.5,'1m',
     '[긴급] API 조회 실패가 반복됩니다',
     '최근 2분 5xx 추정 건수={{ $values.A.Value }}. API 실패 로그의 requestId, errorCode, sqlState와 배포 시각을 확인하세요. 오류 로그 레벨과 무관한 HTTP 기준입니다. 요청 재시도는 별도 HTTP 요청으로 집계됩니다.')
base='{job="salmonbus/worker"}'
last=f'salmonbus_collection_last_success_timestamp{base}'
first=f'salmonbus_collection_first_attempt_timestamp{base}'
period=f'(max(salmonbus_collection_expected_interval_seconds{base}) <= 300)'
age=f'(time() - (({last} > 0) or on(route_id,route_name) {first}))'
rule('salmonbus-diag-collection-age','노선 정상 관측 갱신 중단',f'{age} and on() {period}',300,'1m',
     '[긴급] {{ $labels.route_name }}번 정상 관측 갱신 중단',
     '정상 관측 또는 재시작 후 첫 수집 시도로부터 {{ $values.A.Value }}초 경과. collection_attempt의 stage와 reason을 확인하세요. 심야 10분 수집 구간은 제외하며 정상 빈 차량 응답은 성공입니다. 한 번도 수집 시도하지 않은 노선은 이 규칙으로 탐지하지 못합니다.')
commit=f'salmonbus_forecast_last_commit_timestamp{base}'
commit_age=f'(time() - (({commit} > 0) or on(route_id,route_name) {first}))'
work=f'((salmonbus_forecast_pending_age_seconds{base} > 0) or (time() - salmonbus_forecast_pending_checked_timestamp{base} > 300))'
rule('salmonbus-diag-forecast-pending','관측 수집 후 예보 배치 처리 중단',
     f'{commit_age} and on(route_id,route_name) {work} and on(route_id,route_name) ((time() - {last}) < 300) and on(route_id,route_name) (salmonbus_collection_usable_rows{base} > 0) and on() {period}',
     300,'1m','[긴급] {{ $labels.route_name }}번 예보 배치 처리 중단',
     '마지막 예보 배치 커밋 또는 재시작 후 첫 수집 시도로부터 {{ $values.A.Value }}초 경과. 관측은 최신이지만 처리 대상이 남았거나 예보 회차 관측이 멈췄습니다. forecast_write_and_commit, 모델 상태, 잠금과 연결 대기를 확인하세요. 정상 0건 예보 커밋은 갱신으로 인정합니다. 한 번도 대상 조회를 하지 않은 노선은 별도 확인합니다.')
expr='(1 - min by(job) (up{job=~"salmonbus/(api|worker|postgres)"}))'
for job in ['api','worker','postgres']:expr+=f' or absent(up{{job="salmonbus/{job}"}})'
rule('salmonbus-diag-monitoring','앱 또는 DB 지표 수집 중단',expr,0,'3m',
     '[확인 필요] {{ $labels.job }} 지표 수집 중단',
     '앱 장애로 단정하지 마세요. Alloy, 관리 포트, DB 통계 수집 권한, 전송 경로를 확인하세요. 지표가 없다는 이유로 정상으로 복구 처리하지 않습니다.',severity='warning')
rules[-1]['no_data_state']='Alerting'
panel_ids={'salmonbus-diag-api-5xx':'2','salmonbus-diag-collection-age':'39','salmonbus-diag-forecast-pending':'40','salmonbus-diag-monitoring':'44'}
for r in rules: r['annotations']['__panelId__']=panel_ids[r['rule_uid']]
(ROOT/'alert-rules.paused.json').write_text(json.dumps(rules,ensure_ascii=False,indent=2)+'\n')
print('Prepared',len(rules),'paused rules; symptom rules require monitoring rule enabled together')
