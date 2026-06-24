#!/usr/bin/env bash
# Smoke E15 — Excel I/O round-trip:
#   seed (4-eyes→PUBLISHED) → export xlsx (distribution) → import xlsx в новый
#   DRAFT (authoring) → проверка. Требует `make up`. Идемпотентно по SFX.
set -euo pipefail

API=http://localhost:8080/api/v1
KC=http://localhost:8090/realms/bank/protocol/openid-connect/token
SFX="${SFX:-e15$(date +%H%M%S)}"
XLSX=/tmp/rdm-e15-$SFX.xlsx

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
token() {
  curl -s -X POST "$KC" -d grant_type=password -d client_id=rdmmesh-ui \
    -d "username=$1" -d password=dev -d scope=openid | jget "['access_token']"
}

echo "==> seed демо-данных (SFX=$SFX)"
SFX="$SFX" bash scripts/seed-demo.sh >/tmp/seed-$SFX.log 2>&1
DOM_NAME="risk_$SFX"
CS_NAME="ifrs9_stages_$SFX"

T_AUTHOR=$(token dev-author)
H="Authorization: Bearer $T_AUTHOR"
CS=$(curl -s -H "$H" "$API/codesets/by-domain/$(curl -s -H "$H" "$API/domains" \
   | python3 -c "import sys,json;[print(d['id']) for d in json.load(sys.stdin) if d['name']=='$DOM_NAME']")" \
   | python3 -c "import sys,json;[print(c['id']) for c in json.load(sys.stdin) if c['name']=='$CS_NAME']")
echo "    codeset id = $CS"

echo "==> EXPORT xlsx (distribution)"
code=$(curl -s -o "$XLSX" -w "%{http_code}" -H "$H" \
  "$API/rdm/$DOM_NAME/$CS_NAME/export?format=xlsx")
echo "    HTTP $code, $(wc -c <"$XLSX") байт"
[ "$code" = 200 ] || { echo "FAIL: export не 200"; exit 1; }
head -c2 "$XLSX" | grep -q 'PK' || { echo "FAIL: не ZIP/xlsx-сигнатура"; exit 1; }

echo "==> новый DRAFT для re-import (клонируется из published — E4)"
V2=$(curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"version":"0.2.0"}' \
  "$API/versions/by-codeset/$CS" | jget "['id']")
echo "    version id = $V2"

echo "==> IMPORT xlsx (authoring bulk-xlsx)"
RES=$(curl -s -X POST -H "$H" \
  -H 'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' \
  --data-binary @"$XLSX" "$API/versions/$V2/items/bulk-xlsx")
echo "    $RES"
echo "$RES" | grep -q '"status":"APPLIED"' || { echo "FAIL: import не APPLIED"; exit 1; }
echo "$RES" | grep -q '"rowsTotal":3' || { echo "FAIL: ожидалось rowsTotal=3"; exit 1; }
# draft клонирован из published → 3 ключа уже есть, upsert обновляет их
ADD=$(echo "$RES" | jget "['rowsAdded']"); UPD=$(echo "$RES" | jget "['rowsUpdated']")
[ $((ADD + UPD)) -eq 3 ] || { echo "FAIL: added+updated != 3"; exit 1; }

echo "==> проверка items в re-imported DRAFT"
TOTAL=$(curl -s -H "$H" "$API/versions/$V2/items?page=0&size=10" | jget "['total']")
echo "    total=$TOTAL"
[ "$TOTAL" = 3 ] || { echo "FAIL: ожидалось 3 item'а"; exit 1; }

echo "==> негатив: битый xlsx → 422 REJECTED"
echo "not-an-xlsx" > /tmp/bad-$SFX.xlsx
BAD=$(curl -s -X POST -H "$H" \
  -H 'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' \
  --data-binary @/tmp/bad-$SFX.xlsx "$API/versions/$V2/items/bulk-xlsx")
echo "    $BAD"
echo "$BAD" | grep -q '"status":"REJECTED"' || { echo "FAIL: битый xlsx не REJECTED"; exit 1; }

echo "==> негатив: format=parquet → 501"
P=$(curl -s -o /dev/null -w "%{http_code}" -H "$H" \
  "$API/rdm/$DOM_NAME/$CS_NAME/export?format=parquet")
[ "$P" = 501 ] || { echo "FAIL: parquet не 501 (got $P)"; exit 1; }

echo
echo "SMOKE E15 OK — export xlsx ✓ / import xlsx ✓ / round-trip 3 items ✓ / negatives ✓"
