#!/usr/bin/env bash
set -euo pipefail
source /local-tools/settings.sh
load_settings mongodb
[[ $MONGO_INITDB_ROOT_USERNAME == salmonbus_local ]] || fail '로컬 개발 환경에서만 실행할 수 있습니다.'

result=$(mongosh --quiet --host 127.0.0.1 --eval '
  const admin = db.getSiblingDB("admin");
  const auth = admin.auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD);
  if (auth !== 1 && auth?.ok !== 1) throw new Error("LOCAL_AUTH_FAILED");
  const result = db.getSiblingDB("salmonbus_chat").getCollection("messages").deleteMany({});
  print(JSON.stringify({status: "ok", deletedMessages: result.deletedCount}));
' 2>/dev/null) || fail '로컬 채팅 데이터를 정리하지 못했습니다. API와 worker는 중지 상태로 유지합니다.'
printf '%s\n' "$result"
