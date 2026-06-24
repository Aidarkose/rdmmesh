#!/usr/bin/env bash
# rdmmesh — заполняет authority/domain-role-directory для ВСЕХ существующих
# доменов: dev-steward → STEWARD, dev-owner → BUSINESS_OWNER. Без создания
# доменов/CodeSet'ов — только directory.
#
# Зачем: endpoint /admin/domain-role-directory/reload — TRUNCATE+INSERT, т.е.
# заменяет ВСЕ записи разом. Если для одного домена сделать reload — directory
# для всех остальных опустеет. Этот скрипт собирает entries по всем доменам
# через GET /domains, поэтому ни один не теряется. Идемпотентно.
#
# Использование:
#   make up && make seed-domain-roles
#
# Требует поднятый стек (make up) и dev-юзеров dev-admin/dev-steward/dev-owner
# в Keycloak (создаются bootstrap'ом по умолчанию).

set -euo pipefail

KC=http://localhost:8090/realms/bank/protocol/openid-connect/token
API=http://localhost:8080/api/v1

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

token() {
  curl -s -X POST "$KC" -d grant_type=password -d client_id=rdmmesh-ui \
    -d "username=$1" -d "password=dev" -d scope=openid | jget "['access_token']"
}

echo "==> получаю токены"
T_ADMIN=$(token dev-admin)
T_STEWARD=$(token dev-steward)
T_OWNER=$(token dev-owner)

ME_STEWARD=$(curl -s -H "Authorization: Bearer $T_STEWARD" "$API/auth/me" | jget "['omUserId']")
ME_OWNER=$(curl -s -H "Authorization: Bearer $T_OWNER" "$API/auth/me" | jget "['omUserId']")
echo "    steward om_user_id = $ME_STEWARD"
echo "    owner   om_user_id = $ME_OWNER"

echo "==> собираю список доменов через GET /domains"
DOMAINS_JSON=$(curl -s -H "Authorization: Bearer $T_ADMIN" "$API/domains")
COUNT=$(printf '%s' "$DOMAINS_JSON" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
echo "    найдено доменов: $COUNT"

if [ "$COUNT" -eq 0 ]; then
  echo "!! Доменов нет — нечего сидить. Создай домен через UI или make seed-credit-risk."
  exit 1
fi

echo "==> формирую entries (по 2 записи на домен: STEWARD + BUSINESS_OWNER)"
# python3 -c "..." не читает stdin как скрипт, поэтому пайп с JSON работает
# нормально. (heredoc <<EOF этого делать нельзя — он сам занимает stdin.)
ENTRIES=$(printf '%s' "$DOMAINS_JSON" | python3 -c '
import json, sys
steward, owner = sys.argv[1], sys.argv[2]
domains = json.load(sys.stdin)
out = []
for d in domains:
    omid = d.get("om_domain_id")
    if not omid:
        continue
    out.append({"om_domain_id": omid, "role": "STEWARD",
                "om_user_id": steward, "username": "dev-steward",
                "display_name": "Dev Steward"})
    out.append({"om_domain_id": omid, "role": "BUSINESS_OWNER",
                "om_user_id": owner, "username": "dev-owner",
                "display_name": "Dev Owner"})
print(json.dumps({"entries": out}))
' "$ME_STEWARD" "$ME_OWNER")

echo "==> POST /admin/domain-role-directory/reload (TRUNCATE+INSERT глобально)"
HTTP=$(curl -s -o /tmp/seed-roles-resp.txt -w "%{http_code}" \
  -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "$ENTRIES" "$API/admin/domain-role-directory/reload")

if [ "$HTTP" != "200" ] && [ "$HTTP" != "204" ]; then
  echo "!! reload вернул HTTP $HTTP:"
  cat /tmp/seed-roles-resp.txt; echo
  exit 1
fi

echo "==> готово: справочник ролей заполнен для всех $COUNT доменов"
