#!/usr/bin/env python3
"""Rebuild the local mock's station list from the checked-in official CSV baseline."""
import csv
import gzip
import io
import json
from pathlib import Path

base = Path(__file__).resolve().parent
with gzip.open(base / 'stations-gbis-20261020.csv.gz', 'rt', encoding='utf-8') as source:
    rows = list(csv.DictReader(source))

def region(city):
    if city.startswith('서울'):
        return '서울'
    if city.startswith('인천'):
        return '인천'
    return city.split()[-1].removesuffix('시').removesuffix('군')

stations = []
for row in rows:
    identifier = row['정류장번호']
    if not identifier.startswith('GGB') or identifier.startswith('GGB277'):
        continue
    number = row['모바일단축번호']
    stations.append({
        'stationId': identifier[3:], 'stationName': row['정류장명'],
        'x': float(row['경도']), 'y': float(row['위도']),
        'mobileNo': number.zfill(5) if number.isdigit() else number,
        'regionName': region(row['도시명']), 'centerYn': None,
    })
dataset = {
    'version': 'local-station-around-v1',
    'source': {
        'type': 'official-baseline-replay', 'dataDate': '2026-10-20',
        'url': 'https://www.data.go.kr/data/15067528/fileData.do',
        'note': 'Local mock lookup from baseline; not a fresh real GBIS observation',
    },
    'stations': stations,
}
encoded = json.dumps(dataset, ensure_ascii=False, separators=(',', ':')).encode()
(base / 'station-baseline-20261020.json.gz').write_bytes(gzip.compress(encoded, mtime=0))
print(f'Local station baseline: {len(stations)} rows')
