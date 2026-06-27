#!/usr/bin/env bash
# Настройка интеграции OM→rdmmesh (часть 1): тонкое уведомление OM Alert + pull.
#
# Делает:
#   1. логинится в OpenMetadata как admin (basic auth);
#   2. генерирует долгоживущий Personal Access Token (bot-token для rdmmesh);
#   3. создаёт/обновляет OM Alert `Domain_and_Roles_sync_for_RDMmesh` (Notification),
#      вебхук → http://rdmmesh-service:8080/api/v1/webhooks/om/catalog-sync;
#   4. печатает строки env для rdmmesh-service (RDM_OM_BASE_URL / RDM_OM_BOT_TOKEN).
#
# По уведомлению rdmmesh сам тянет ТОЛЬКО домены и роли из нативного OM REST API
# (Bearer bot-token) и апсертит в catalog.domain / catalog.om_role.
#
# Примечание по фильтрации: OM 1.12.5 НЕ регистрирует `domain`/`role` как
# подписываемые ресурсы уведомлений (валидны all/table/user/glossaryTerm/...),
# и фильтра по типу сущности для `all` нет. Поэтому алерт создаётся на
# resources:["all"], а сужение «только домены и роли» выполняется на стороне pull
# (ровно как в ТЗ: «выгружает данные только по доменам и ролям»).
#
# Использование:
#   OM_URL=http://localhost:8585 OM_ADMIN_EMAIL=admin@open-metadata.org \
#   OM_ADMIN_PASSWORD=admin bash scripts/om-catalog-sync-setup.sh
set -euo pipefail

OM_URL="${OM_URL:-http://localhost:8585}"
OM_ADMIN_EMAIL="${OM_ADMIN_EMAIL:-admin@open-metadata.org}"
OM_ADMIN_PASSWORD="${OM_ADMIN_PASSWORD:-admin}"
RDM_WEBHOOK="${RDM_WEBHOOK:-http://rdmmesh-service:8080/api/v1/webhooks/om/catalog-sync}"
ALERT_NAME="${ALERT_NAME:-Domain_and_Roles_sync_for_RDMmesh}"

jqpy() { python3 -c "import sys,json;$1"; }

echo "==> login $OM_ADMIN_EMAIL @ $OM_URL"
B64=$(printf '%s' "$OM_ADMIN_PASSWORD" | base64)
TOK=$(curl -s -X POST "$OM_URL/api/v1/users/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$OM_ADMIN_EMAIL\",\"password\":\"$B64\"}" \
      | jqpy "print(json.load(sys.stdin)['accessToken'])")
[ -n "$TOK" ] || { echo "login failed"; exit 1; }

echo "==> generate Personal Access Token (Unlimited)"
PAT=$(curl -s -X PUT "$OM_URL/api/v1/users/security/token" -H "Authorization: Bearer $TOK" \
        -H 'Content-Type: application/json' \
        -d '{"tokenName":"rdmmesh-catalog-sync","JWTTokenExpiry":"Unlimited"}' \
      | jqpy "print(json.load(sys.stdin)['jwtToken'])")
[ -n "$PAT" ] || { echo "PAT generation failed"; exit 1; }

echo "==> create/update alert $ALERT_NAME"
read -r -d '' BODY <<JSON || true
{
  "name": "$ALERT_NAME",
  "displayName": "Domain and Roles sync for RDMmesh",
  "description": "Тонкое уведомление в RDMmesh об изменениях метаданных; RDMmesh по нему сам тянет ТОЛЬКО домены и роли из нативного OM REST API.",
  "alertType": "Notification",
  "provider": "user",
  "enabled": true,
  "resources": ["all"],
  "input": { "actions": [], "filters": [] },
  "destinations": [
    { "category": "External", "type": "Webhook",
      "config": { "endpoint": "$RDM_WEBHOOK" } }
  ]
}
JSON
curl -s -X PUT "$OM_URL/api/v1/events/subscriptions" -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' -d "$BODY" \
  | jqpy "d=json.load(sys.stdin);print('   alert id=',d.get('id'),'enabled=',d.get('enabled'))"

cat <<EOF

==> Готово. Пропишите rdmmesh-service эти env и перезапустите контейнер:

  RDM_OM_BASE_URL=$OM_URL/
  RDM_OM_BOT_TOKEN=$PAT

Проверка вручную (тонкий триггер):
  curl -s -X POST $RDM_WEBHOOK -H 'Content-Type: application/json' -d '{}'
EOF
