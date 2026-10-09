#!/usr/bin/env python3
"""Generate an additive dashboard; no server calls, no existing panel edits."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parent
P = {'type':'prometheus','uid':'grafanacloud-prom'}
L = {'type':'loki','uid':'grafanacloud-logs'}
panels=[]
y=0

def row(title):
    global y
    panels.append({'id':len(panels)+1,'type':'row','title':title,'collapsed':False,'panels':[],
                   'gridPos':{'x':0,'y':y,'w':24,'h':1}});y+=1

def panel(title,expr,unit='short',kind='timeseries',width=12,legend='{{application}} {{pool}}',description=''):
    global y
    # Sequential half-width panels, no overlaps.
    x=12 if width==12 and panels and panels[-1]['type']!='row' and panels[-1]['gridPos']['w']==12 and panels[-1]['gridPos']['x']==0 else 0
    py=panels[-1]['gridPos']['y'] if x else y
    h=7 if kind not in ('stat','gauge') else 5
    instant=kind in ('stat','gauge','bargauge','table')
    targets=[]
    for i,e in enumerate(expr if isinstance(expr,list) else [expr]):
        targets.append({'refId':chr(65+i),'datasource':P,'expr':e,'editorMode':'code',
                        'instant':instant,'range':not instant,'legendFormat':legend,'format':'time_series'})
    options={'legend':{'displayMode':'table','placement':'bottom','calcs':['lastNotNull','max']},
             'tooltip':{'mode':'multi','sort':'desc'}}
    if instant:options={'reduceOptions':{'calcs':['lastNotNull'],'fields':'','values':False},'orientation':'horizontal','displayMode':'gradient','showUnfilled':True}
    panels.append({'id':len(panels)+1,'title':title,'description':description,'type':kind,'datasource':P,
        'gridPos':{'x':x,'y':py,'w':width,'h':h},'targets':targets,'options':options,
        'fieldConfig':{'defaults':{'unit':unit,'noValue':'미수집 / 미확인','color':{'mode':'palette-classic'},
                                  'custom':{'drawStyle':'line','lineWidth':2,'fillOpacity':8,'spanNulls':False}},'overrides':[]}})
    y=max(y,py+h)

def logs(title,query):
    global y
    panels.append({'id':len(panels)+1,'type':'logs','title':title,'datasource':L,'gridPos':{'x':0,'y':y,'w':24,'h':8},
        'targets':[{'refId':'A','expr':query,'queryType':'range','direction':'backward','maxLines':100,'datasource':L}],
        'options':{'showTime':True,'showLevel':True,'wrapLogMessage':True,'sortOrder':'Descending','dedupStrategy':'none','enableLogDetails':True}});y+=8

pg='{job="salmonbus/postgres",instance="salmonbus-db"}'
rds='{dimension_DBInstanceIdentifier="salmonbus-db"}'
def aws(name,stat='average'):return f'last_over_time(aws_rds_{name}_{stat}{rds}[15m])'
row('01 사용자 영향과 DB 연결')
panel('API 5xx 비율', '100 * (sum(rate(http_server_requests_seconds_count{job="salmonbus/api",status=~"5.."}[5m])) or (0 * sum(rate(http_server_requests_seconds_count{job="salmonbus/api"}[5m])))) / sum(rate(http_server_requests_seconds_count{job="salmonbus/api"}[5m]))','percent',legend='5xx')
panel('DB 접속 가능 여부',f'pg_up{pg}',kind='state-timeline',legend='DB 접속')
panel('연결을 기다리는 요청', 'hikaricp_connections_pending{job=~"salmonbus/(api|worker)"}')
panel('빌려간 연결 / 최대 연결', ['hikaricp_connections_active{job=~"salmonbus/(api|worker)"}','hikaricp_connections_max{job=~"salmonbus/(api|worker)"}'],legend='{{application}} {{pool}} {{__name__}}')
panel('연결 획득 평균 시간','rate(hikaricp_connections_acquire_seconds_sum{job=~"salmonbus/(api|worker)"}[5m]) / rate(hikaricp_connections_acquire_seconds_count{job=~"salmonbus/(api|worker)"}[5m])','s')
panel('연결 사용 평균 시간','rate(hikaricp_connections_usage_seconds_sum{job=~"salmonbus/(api|worker)"}[5m]) / rate(hikaricp_connections_usage_seconds_count{job=~"salmonbus/(api|worker)"}[5m])','s')
row('02 읽기와 쓰기 / 디스크 성능')
panel('읽기 / 쓰기 처리량',[aws('read_throughput'),aws('write_throughput')],'Bps',legend='{{__name__}}')
panel('읽기 / 쓰기 지연',[aws('read_latency'),aws('write_latency')],'s',legend='{{__name__}}')
panel('읽기 / 쓰기 횟수',[aws('read_iops'),aws('write_iops')],'iops',legend='{{__name__}}')
panel('디스크 대기열',aws('disk_queue_depth','maximum'),legend='대기 IO')
panel('EBS 처리량 크레딧 잔량',aws('ebsbyte_balance_percent','minimum'),'percent','gauge',legend='ByteBalance')
panel('EBS IO 횟수 크레딧 잔량',aws('ebsiobalance_percent','minimum'),'percent','gauge',legend='IOBalance')
row('03 쓰기와 커밋 / 잠금 / 백그라운드 정리')
panel('커밋 / 롤백 속도',[f'rate(salmonbus_pg_database_commits_total{pg}[5m])',f'rate(salmonbus_pg_database_rollbacks_total{pg}[5m])'],'ops',legend='{{__name__}}')
panel('WAL 생성량',f'rate(salmonbus_pg_wal_bytes_total{pg}[5m])','Bps',legend='WAL')
panel('앱별 잠금 / IO 대기',[f'salmonbus_pg_app_lock_waiters{pg}',f'salmonbus_pg_app_io_waiters{pg}'],legend='{{application}} {{state}} {{__name__}}')
panel('다른 작업을 막는 트랜잭션의 나이',f'salmonbus_pg_blocking_oldest_blocker_transaction_seconds{pg}','s',legend='최장 차단 트랜잭션')
panel('잠금 충돌 / 임시 파일',[f'increase(salmonbus_pg_database_deadlocks_total{pg}[5m])',f'increase(salmonbus_pg_database_temp_files_total{pg}[5m])'],legend='{{__name__}}')
panel('테이블별 수정 속도',[f'rate(salmonbus_pg_table_updates_total{pg}[5m])',f'rate(salmonbus_pg_table_inserts_total{pg}[5m])',f'rate(salmonbus_pg_table_deletes_total{pg}[5m])'],'ops',legend='{{table}} {{__name__}}')
panel('테이블별 dead 행 추정',f'salmonbus_pg_table_dead_rows_estimate{pg}',kind='bargauge',legend='{{table}}')
panel('자동 vacuum 경과',f'time() - (salmonbus_pg_table_last_autovacuum_timestamp_seconds{pg} > 0)','s',legend='{{table}}')
row('04 메모리 / 저장 공간 / 재시작')
panel('가용 메모리 / swap',[aws('freeable_memory','minimum'),aws('swap_usage','maximum')],'bytes',legend='{{__name__}}')
panel('DB 저장 공간 여유',aws('free_storage_space','minimum'),'bytes',kind='stat',legend='디스크 여유')
panel('RDS CPU 사용률',aws('cpuutilization','maximum'),'percent',legend='CPU')
panel('선택 기간 DB 시작 시각 변경 횟수',f'changes(salmonbus_pg_database_start_timestamp_seconds{pg}[$__range])',kind='stat',legend='관측된 변경')
row('05 어떤 SQL과 업무인가? 선택한 시간 범위의 증가량')
for title,metric,unit in [('버퍼 읽기 상위 SQL','blocks_read_total','short'),('버퍼 수정 상위 SQL','blocks_dirtied_total','short'),('실행 시간 상위 SQL','execution_seconds_total','s'),('WAL 생성 상위 SQL','wal_bytes_total','bytes'),('임시 파일 쓰기 상위 SQL','temp_blocks_written_total','short'),('실행 횟수 상위 SQL','calls_total','short')]:
    panel(title,f'topk(10, increase(salmonbus_pg_statement_{metric}{pg}[$__range]))',unit,'bargauge',legend='{{query_origin}} | {{query_id}}')
panel('현재 실행 중인 업무와 대기',f'salmonbus_pg_running_active_sessions{pg}',legend='{{application}} {{query_origin}} {{wait_type}}')
panel('상한 때문에 제외된 SQL 수',f'salmonbus_pg_statement_coverage_not_exported{pg}',kind='stat',legend='미수집 SQL')
row('06 노선별 수집과 예보 / 원인 로그')
panel('마지막 정상 관측의 나이','time() - salmonbus_collection_last_success_timestamp{job="salmonbus/worker"}','s',legend='{{route_name}}번')
panel('미발행 관측의 나이','salmonbus_forecast_pending_age_seconds{job="salmonbus/worker"}','s',legend='{{route_name}}번')
logs('API 실패 원인','{unit="salmonbus-api.service"} |= "event=api_failure "')
logs('DB 저장과 작업 실패','{unit="salmonbus-worker.service"} |~ "event=(worker_operation|collection_attempt) .*status=FAILED"')
row('07 수집 상태 / 빈 화면과 정상 0 구분')
panel('API 지표 수집', 'min(up{job="salmonbus/api"})',kind='stat',legend='API up')
panel('worker 지표 수집', 'min(up{job="salmonbus/worker"})',kind='stat',legend='worker up')
panel('추가 RDS 원본 표본 경과', 'time() - max_over_time(timestamp(aws_rds_read_throughput_average{dimension_DBInstanceIdentifier="salmonbus-db"})[30m:1m])','s',kind='stat',legend='원본 표본 나이')
panel('SQL 통계 표본 경과', f'time() - timestamp(salmonbus_pg_statement_coverage_statements{pg})','s',kind='stat',legend='SQL 표본 나이')
help_text='''### 읽는 순서
사용자 영향 → 연결 대기 → 읽기와 쓰기 → 잠금 → 메모리 → SQL과 업무 순서로 같은 시간을 선택합니다.

### 아직 미검증인 것
추가 RDS 지표와 PostgreSQL 진단 쿼리는 서버 적용 전입니다. 빈 패널은 0이나 정상으로 해석하지 않습니다. SQL별 지표는 pg_stat_statements 활성화와 권한 확인 후 수집합니다.

### 해석의 한계
- EBS 잔량은 요청 크기 제한이 아닙니다. 잔량 감소만으로 장애 원인을 확정하지 않습니다.
- SQL 버퍼 읽기는 물리 디스크 읽기가 아닙니다. 버퍼 수정과 WAL 생성량도 실제 디스크 쓰기량과 다릅니다.
- query_origin은 소스 코드에 붙인 업무 태그입니다. 동일 SQL이 여러 업무에서 쓰이면 pg_stat_statements가 합칠 수 있습니다. 현재 실행 중인 업무 표본과 앱 로그를 함께 봅니다.
- SQL 상위 표는 수집된 최대 100개 SQL의 기간 증가량입니다. 제외, 통계 초기화, 재시작, 수집 누락 때 전체 순위를 보장하지 않습니다. 짧은 SQL은 60초 활동 표본에 안 잡힐 수 있습니다.
- 연결 사용 시간은 연결을 빌린 전체 시간이며 SQL 실행이나 순수 커밋 시간과 같지 않습니다. DB idle은 앱이 연결을 반납했다는 뜻이 아닙니다.
- 노선별 관측 값은 재시작 후 메모리에서 다시 시작합니다. 미발행 나이는 한 회차 전 조회 결과일 수 있고 지원 모델이 조회한 범위만 나타냅니다.
- 새 알림은 일시 정지 상태로 준비합니다. 배포, 지표 수집, 장애/복구 검증 후 활성화합니다. 테스트 알림은 발송하지 않았습니다.

### 비용과 복구
정상 SQL 원문/바인딩 값은 보내지 않습니다. DB 통계 60초, 추가 AWS 지표 5분, 로그 패널 최대 100줄입니다. SQL은 매 수집 시 최대 100개이며 기간 전체의 시계열 수 상한은 아닙니다. 기존 사용량과 합산해야 합니다. AWS 조회와 전송 비용은 별도입니다.
문제 시 새 수집 블록만 제거하고 Alloy 검증 후 reload합니다. SQL 태그와 ApplicationName은 진단 표식이며 업무 결과를 바꾸지 않습니다. 재시작/확장 설치는 자동 실행하지 않습니다.
'''
textpanel={'id':len(panels)+2,'type':'text','title':'해석과 적용 상태','gridPos':{'x':0,'y':y+1,'w':24,'h':18},'options':{'mode':'markdown','content':help_text}}
panels.append({'id':len(panels)+1,'type':'row','title':'도움말과 적용 상태','collapsed':True,'panels':[textpanel],'gridPos':{'x':0,'y':y,'w':24,'h':1}})
# Color only explicit states; an absent sample keeps the unknown label.
for item in panels:
    if item['title'] in ('DB 접속 가능 여부', 'API 지표 수집', 'worker 지표 수집'):
        ok='접속 가능' if item['title']=='DB 접속 가능 여부' else '수집 정상'
        bad='접속 실패' if item['title']=='DB 접속 가능 여부' else '수집 실패'
        item['fieldConfig']['defaults']['mappings']=[{'type':'value','options':{'0':{'text':bad,'color':'red'},'1':{'text':ok,'color':'green'}}}]
    if item['type']=='gauge' and item['fieldConfig']['defaults']['unit']=='percent':
        item['fieldConfig']['defaults'].update({'min':0,'max':100})

# Keep detailed sections folded initially. All explanations stay in one help section.
folded=[]
active=None
for item in panels:
    if item['type']=='row':
        active=None
        if item['title'].startswith(('03 ', '04 ', '05 ')):
            item['collapsed']=True
            active=item
        folded.append(item)
    elif active is not None: active['panels'].append(item)
    else: folded.append(item)
panels=folded
d={'uid':'salmonbus-db-diagnostics','title':'Salmonbus DB 원인 분석','schemaVersion':42,'version':0,'editable':True,
   'tags':['salmonbus','diagnostics'],'time':{'from':'now-1h','to':'now'},'timezone':'Asia/Seoul','refresh':'1m','graphTooltip':1,
   'links':[{'type':'link','title':'기존 운영 화면','url':'/d/salmonbus-prod','keepTime':True},{'type':'link','title':'노선과 예보','url':'/d/salmonbus-service','keepTime':True}],
   'templating':{'list':[]},'panels':panels}
(ROOT/'dashboard.json').write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
print('Wrote',len(panels),'panels/rows; no external changes')
