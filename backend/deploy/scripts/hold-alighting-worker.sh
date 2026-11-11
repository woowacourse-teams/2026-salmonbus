#!/usr/bin/env bash
# API 마이그레이션 전에 Worker 호스트에서 실행한다.
set -euo pipefail

if [ "$(id -u)" != 0 ]; then
  echo 'root로 실행하세요.' >&2
  exit 1
fi

hold_file=/etc/salmonbus/alighting-cutover-hold
dropin_dir=/etc/systemd/system/salmonbus-worker.service.d
mkdir -p /etc/salmonbus
install -d -m 755 -o root -g root "$dropin_dir"
touch "$hold_file"
umask 022
printf '[Unit]\nConditionPathExists=!%s\n' "$hold_file" > "$dropin_dir/alighting-cutover-hold.conf"
systemctl daemon-reload
systemctl stop salmonbus-worker
