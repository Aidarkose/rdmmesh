#!/usr/bin/env bash
# rdmmesh — демо-данные для UI: домен + CodeSet ifrs9_stages + версия,
# прогнанная через полный 4-eyes flow до PUBLISHED. Идемпотентно по суффиксу.
set -euo pipefail

KC=http://localhost:8090/realms/bank/protocol/openid-connect/token
API=http://localhost:8080/api/v1
SFX="${SFX:-$(date +%H%M%S)}"
OMID=$(python3 -c "import uuid;print(uuid.uuid4())")

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

token() {
  curl -s -X POST "$KC" -d grant_type=password -d client_id=rdmmesh-ui \
    -d "username=$1" -d "password=dev" -d scope=openid | jget "['access_token']"
}

echo "==> получаю токены"
T_ADMIN=$(token dev-admin)
T_AUTHOR=$(token dev-author)
T_STEWARD=$(token dev-steward)
T_OWNER=$(token dev-owner)

echo "==> создаю домен (dev-admin, bootstrap)"
DOM=$(curl -s -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"om_domain_id\":\"$OMID\",\"name\":\"risk_$SFX\",\"display_name\":\"Risk ($SFX)\"}" \
  "$API/domains" | jget "['id']")
echo "    domain id = $DOM"

echo "==> E17: справочник ролей домена (BR-21) — резолвлю om_user_id согласующих"
ME_STEWARD=$(curl -s -H "Authorization: Bearer $T_STEWARD" "$API/auth/me" | jget "['omUserId']")
ME_OWNER=$(curl -s -H "Authorization: Bearer $T_OWNER" "$API/auth/me" | jget "['omUserId']")
echo "    steward om_user_id = $ME_STEWARD"
echo "    owner   om_user_id = $ME_OWNER"

echo "==> reload справочника ролей домена (dev-admin, полная замена TRUNCATE+INSERT)"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"entries\":[
        {\"om_domain_id\":\"$OMID\",\"role\":\"STEWARD\",\"om_user_id\":\"$ME_STEWARD\",\"username\":\"dev-steward\",\"display_name\":\"Dev Steward\"},
        {\"om_domain_id\":\"$OMID\",\"role\":\"BUSINESS_OWNER\",\"om_user_id\":\"$ME_OWNER\",\"username\":\"dev-owner\",\"display_name\":\"Dev Owner\"}
      ]}" \
  "$API/admin/domain-role-directory/reload"

echo "==> создаю CodeSet ifrs9_stages (dev-author)"
CS=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d "{\"name\":\"ifrs9_stages_$SFX\",\"display_name\":\"IFRS9 Stages ($SFX)\",\"hierarchy_mode\":\"NONE\",
       \"initial_schema\":{\"type\":\"object\",\"required\":[\"stage\"],
         \"properties\":{\"stage\":{\"type\":\"string\",\"enum\":[\"1\",\"2\",\"3\"]}}}}" \
  "$API/codesets/by-domain/$DOM" | jget "['id']")
echo "    codeset id = $CS"

echo "==> создаю DRAFT-версию (dev-author)"
V=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d '{}' "$API/versions/by-codeset/$CS" | jget "['id']")
echo "    version id = $V"

echo "==> добавляю items S1/S2/S3"
for n in 1 2 3; do
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d "{\"key_parts\":[\"S$n\"],\"label_ru\":\"Стадия $n\",\"label_en\":\"Stage $n\",\"attributes\":{\"stage\":\"$n\"}}" \
    "$API/versions/$V/items"
done

echo "==> 4-eyes: submit (author) — адресно: steward=dev-steward, owner=dev-owner (BR-21)"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d "{\"to\":\"IN_REVIEW\",\"comment\":\"готово к ревью\",
       \"assignee\":{\"domain_id\":\"$DOM\",\"steward_om_user_id\":\"$ME_STEWARD\",\"owner_om_user_id\":\"$ME_OWNER\"}}" \
  "$API/versions/$V/transitions"
echo "==> steward_approve (steward)"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_STEWARD" -H 'Content-Type: application/json' \
  -d '{"to":"STEWARD_APPROVED","comment":"схема ок"}' "$API/versions/$V/transitions"
echo "==> owner_approve (owner) → авто-publish"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_OWNER" -H 'Content-Type: application/json' \
  -d '{"to":"OWNER_APPROVED","comment":"бизнес-аппрув"}' "$API/versions/$V/transitions"

echo "==> статус версии:"
curl -s -H "Authorization: Bearer $T_AUTHOR" "$API/versions/$V" | jget "['status']"
echo "ГОТОВО. Домен 'Risk ($SFX)' → CodeSet 'IFRS9 Stages ($SFX)' → версия PUBLISHED."
