#!/usr/bin/env bash
# rdmmesh — интеграция с OpenMetadata: переносит домены, владельцев/стьюардов и
# учётки пользователей из OM (data-catalog / om-catalog) в rdmmesh и привязывает
# справочники ECL к настоящему домену ECL.
#
# Что делает (идемпотентно по KC-юзерам и доменам):
#   1. Читает из OM (live) домены ECL и Airfly: их UUID, owner (DomainOwner) и
#      expert (DomainDataSteward).
#   2. Создаёт в Keycloak (realm bank) учётки 4 человеческих OM-пользователей с
#      группами RDM_OWNER / RDM_STEWARD (DomainOwner→RDM_OWNER, expert→RDM_STEWARD).
#   3. Заводит в rdmmesh домены ecl и airfly с om_domain_id = UUID домена в OM.
#   4. Резолвит om_user_id каждого юзера (login → /auth/me) и грузит
#      domain-role-directory: ecl(owner+steward), airfly(owner+steward).
#   5. Пересоздаёт 12 справочников ECL (bootstrap/seed/ecl) под доменом ecl с
#      реальным 4-eyes: автор dev-author, steward/owner — пользователи из OM.
#
# Требует поднятый стек rdmmesh (make up) и доступный OpenMetadata.
# Порты rdmmesh переопределяемы (KC_PORT/API_PORT), как в seed-ecl-references.sh.

set -euo pipefail

KC_PORT="${KC_PORT:-8090}"
API_PORT="${API_PORT:-8080}"
KC_BASE="http://localhost:${KC_PORT}"
API="http://localhost:${API_PORT}/api/v1"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

OM_URL="${OM_URL:-http://localhost:8585}"
OM_EMAIL="${OM_EMAIL:-admin@open-metadata.org}"
OM_PASS_B64="${OM_PASS_B64:-YWRtaW4=}"   # base64('admin')

SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../bootstrap/seed/ecl" && pwd)"

CODESETS=(
  r_ecl_branch_sgmnt r_ecl_prdct_sgmnt r_ecl_pledge_group r_ecl_pledge_group_quality
  r_lnk_branch_to_ecl_sgmnt r_lnk_prdct_to_ecl_sgmnt r_lnk_pledge_to_ecl_group
  r_coef_pd r_coef_pd_macro r_coef_lgd r_coef_ead_ttd r_coef_indv_reserves
)

# Косметика имён для KC-учёток (username → "Имя Фамилия"). Сами owner/expert и
# их привязка к доменам берутся из OM динамически — это лишь firstName/lastName.
declare -A FULLNAME=(
  [aigerim.bekova]="Aigerim Bekova"
  [dana.akhmetova]="Dana Akhmetova"
  [marat.suleimenov]="Marat Suleimenov"
  [timur.iskakov]="Timur Iskakov"
)

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# ── OM ────────────────────────────────────────────────────────────────────────
om_token() {
  python3 - "$OM_URL" "$OM_EMAIL" "$OM_PASS_B64" <<'PY'
import json,urllib.request,sys
url,email,pw=sys.argv[1],sys.argv[2],sys.argv[3]
req=urllib.request.Request(url+"/api/v1/users/login",
    data=json.dumps({"email":email,"password":pw}).encode(),
    headers={"Content-Type":"application/json"})
print(json.load(urllib.request.urlopen(req))["accessToken"])
PY
}

# om_domain <Name> → "uuid|owner_username|expert_username"
om_domain() {
  curl -s -H "Authorization: Bearer $OMT" "$OM_URL/api/v1/domains/name/$1?fields=owners,experts" | python3 -c "
import sys,json
d=json.load(sys.stdin)
owner=(d.get('owners') or [{}])[0].get('name','')
expert=(d.get('experts') or [{}])[0].get('name','')
print(f\"{d['id']}|{owner}|{expert}\")"
}

# ── Keycloak ─────────────────────────────────────────────────────────────────
kc_admin_token() {
  curl -s -X POST "$KC_BASE/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d "username=$KC_ADMIN_USER" -d "password=$KC_ADMIN_PASS" | jget "['access_token']"
}

kc_user_token() {  # $1=username  (пароль dev)
  curl -s -X POST "$KC_BASE/realms/bank/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=rdmmesh-ui \
    -d "username=$1" -d "password=dev" -d scope=openid | jget "['access_token']"
}

# create_kc_user <username> <email> <group>
create_kc_user() {
  local username="$1" email="$2" group="$3"
  local full="${FULLNAME[$username]:-$username}"; local first="${full%% *}"; local last="${full#* }"
  local uid
  uid=$(curl -s -H "Authorization: Bearer $KCADM" \
        "$KC_BASE/admin/realms/bank/users?username=$username&exact=true" | jget "[0]['id']" 2>/dev/null || true)
  if [ -z "${uid:-}" ] || [ "$uid" = "None" ]; then
    curl -s -o /dev/null -X POST -H "Authorization: Bearer $KCADM" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$username\",\"enabled\":true,\"email\":\"$email\",\"firstName\":\"$first\",\"lastName\":\"$last\",
           \"credentials\":[{\"type\":\"password\",\"value\":\"dev\",\"temporary\":false}]}" \
      "$KC_BASE/admin/realms/bank/users"
    uid=$(curl -s -H "Authorization: Bearer $KCADM" \
          "$KC_BASE/admin/realms/bank/users?username=$username&exact=true" | jget "[0]['id']")
    echo "    создан KC-юзер $username ($group)"
  else
    echo "    KC-юзер $username уже есть"
  fi
  local gid="${GROUP_ID[$group]}"
  curl -s -o /dev/null -X PUT -H "Authorization: Bearer $KCADM" \
    "$KC_BASE/admin/realms/bank/users/$uid/groups/$gid"
}

# ── rdmmesh ──────────────────────────────────────────────────────────────────
# create_or_get_domain <name> <om_uuid> <display> <label_ru> <label_en> <desc>
create_or_get_domain() {
  local name="$1" omid="$2" disp="$3" lru="$4" len="$5" desc="$6"
  local resp code body id
  resp=$(curl -s -w $'\n%{http_code}' -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
    -d "{\"om_domain_id\":\"$omid\",\"name\":\"$name\",\"display_name\":\"$disp\",
         \"label_ru\":\"$lru\",\"label_en\":\"$len\",\"description\":\"$desc\"}" \
    "$API/domains")
  code="${resp##*$'\n'}"; body="${resp%$'\n'*}"
  if [ "$code" = "201" ]; then
    echo "$body" | jget "['id']"
  else
    # уже существует — берём по имени из списка
    curl -s -H "Authorization: Bearer $T_ADMIN" "$API/domains" | python3 -c "
import sys,json
for d in json.load(sys.stdin):
    if d.get('name')=='$name': print(d['id']); break"
  fi
}

fourEyes() {  # $1=versionId
  local V="$1"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d "{\"to\":\"IN_REVIEW\",\"comment\":\"готово к ревью\",
         \"assignee\":{\"domain_id\":\"$DOM_ECL\",\"steward_om_user_id\":\"$OMU_STEWARD\",\"owner_om_user_id\":\"$OMU_OWNER\"}}" \
    "$API/versions/$V/transitions"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_STEWARD" -H 'Content-Type: application/json' \
    -d '{"to":"STEWARD_APPROVED","comment":"схема ок (steward ECL)"}' "$API/versions/$V/transitions"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_OWNER" -H 'Content-Type: application/json' \
    -d '{"to":"OWNER_APPROVED","comment":"бизнес-аппрув (owner ECL)"}' "$API/versions/$V/transitions"
}

# ── 0. токены ────────────────────────────────────────────────────────────────
echo "==> OM login ($OM_URL)"
OMT=$(om_token)
echo "==> KC admin token"
KCADM=$(kc_admin_token)
echo "==> rdmmesh admin token (dev-admin)"
T_ADMIN=$(kc_user_token dev-admin)
T_AUTHOR=$(kc_user_token dev-author)

# group id map
declare -A GROUP_ID
while IFS='|' read -r gname gid; do GROUP_ID[$gname]="$gid"; done < <(
  curl -s -H "Authorization: Bearer $KCADM" "$KC_BASE/admin/realms/bank/groups" \
    | python3 -c "import sys,json;[print(f\"{g['name']}|{g['id']}\") for g in json.load(sys.stdin)]")

# ── 1. OM-домены ─────────────────────────────────────────────────────────────
echo "==> читаю домены из OM"
IFS='|' read -r OM_ECL_ID ECL_OWNER ECL_STEWARD <<<"$(om_domain ECL)"
IFS='|' read -r OM_AIR_ID AIR_OWNER AIR_STEWARD <<<"$(om_domain Airfly)"
echo "    ECL    uuid=$OM_ECL_ID owner=$ECL_OWNER steward=$ECL_STEWARD"
echo "    Airfly uuid=$OM_AIR_ID owner=$AIR_OWNER steward=$AIR_STEWARD"

# ── 2. KC-учётки ─────────────────────────────────────────────────────────────
echo "==> создаю KC-учётки OM-пользователей"
create_kc_user "$ECL_OWNER"   "$ECL_OWNER@open-metadata.org"   RDM_OWNER
create_kc_user "$ECL_STEWARD" "$ECL_STEWARD@open-metadata.org" RDM_STEWARD
create_kc_user "$AIR_OWNER"   "$AIR_OWNER@open-metadata.org"   RDM_OWNER
create_kc_user "$AIR_STEWARD" "$AIR_STEWARD@open-metadata.org" RDM_STEWARD

# ── 3. домены rdmmesh ────────────────────────────────────────────────────────
echo "==> завожу домены rdmmesh (om_domain_id из OM)"
DOM_ECL=$(create_or_get_domain ecl    "$OM_ECL_ID" "ECL — Кредитный риск (МСФО 9)" "ECL / МСФО9" "ECL / IFRS 9" "Домен оценки кредитных потерь (Expected Credit Loss, МСФО 9). Зеркало OM-домена ECL.")
DOM_AIR=$(create_or_get_domain airfly "$OM_AIR_ID" "Airfly — Авиаперевозки"        "Airfly"      "Airfly"       "Домен авиаперевозок и бронирований. Зеркало OM-домена Airfly.")
echo "    ecl    id=$DOM_ECL"
echo "    airfly id=$DOM_AIR"

# ── 4. om_user_id + role-directory ───────────────────────────────────────────
echo "==> резолвлю om_user_id (login → /auth/me) и гружу role-directory"
omu() { curl -s -H "Authorization: Bearer $(kc_user_token "$1")" "$API/auth/me" | jget "['omUserId']"; }
OMU_ECL_OWNER=$(omu "$ECL_OWNER");   OMU_ECL_STEWARD=$(omu "$ECL_STEWARD")
OMU_AIR_OWNER=$(omu "$AIR_OWNER");   OMU_AIR_STEWARD=$(omu "$AIR_STEWARD")

curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"entries\":[
    {\"om_domain_id\":\"$OM_ECL_ID\",\"role\":\"BUSINESS_OWNER\",\"om_user_id\":\"$OMU_ECL_OWNER\",\"username\":\"$ECL_OWNER\",\"display_name\":\"${FULLNAME[$ECL_OWNER]:-$ECL_OWNER}\"},
    {\"om_domain_id\":\"$OM_ECL_ID\",\"role\":\"STEWARD\",\"om_user_id\":\"$OMU_ECL_STEWARD\",\"username\":\"$ECL_STEWARD\",\"display_name\":\"${FULLNAME[$ECL_STEWARD]:-$ECL_STEWARD}\"},
    {\"om_domain_id\":\"$OM_AIR_ID\",\"role\":\"BUSINESS_OWNER\",\"om_user_id\":\"$OMU_AIR_OWNER\",\"username\":\"$AIR_OWNER\",\"display_name\":\"${FULLNAME[$AIR_OWNER]:-$AIR_OWNER}\"},
    {\"om_domain_id\":\"$OM_AIR_ID\",\"role\":\"STEWARD\",\"om_user_id\":\"$OMU_AIR_STEWARD\",\"username\":\"$AIR_STEWARD\",\"display_name\":\"${FULLNAME[$AIR_STEWARD]:-$AIR_STEWARD}\"}
  ]}" \
  "$API/admin/domain-role-directory/reload"
echo "    role-directory: ECL(owner=$ECL_OWNER,steward=$ECL_STEWARD) Airfly(owner=$AIR_OWNER,steward=$AIR_STEWARD)"

# ── 5. справочники ECL под доменом ecl ───────────────────────────────────────
T_STEWARD=$(kc_user_token "$ECL_STEWARD")
T_OWNER=$(kc_user_token "$ECL_OWNER")
OMU_STEWARD="$OMU_ECL_STEWARD"; OMU_OWNER="$OMU_ECL_OWNER"

echo "==> пересоздаю 12 справочников под доменом ECL (автор dev-author, steward $ECL_STEWARD, owner $ECL_OWNER)"
declare -A VERS
for cs in "${CODESETS[@]}"; do
  cs_file="$SEED_DIR/$cs.codeset.json"; items_file="$SEED_DIR/$cs.items.json"
  n=$(python3 -c "import json;print(len(json.load(open('$items_file'))))")
  CS_ID=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    --data-binary @"$cs_file" "$API/codesets/by-domain/$DOM_ECL" | jget "['id']")
  [ -n "$CS_ID" ] && [ "$CS_ID" != "None" ] || { echo "ОШИБКА: codeset $cs не создан (возможно, уже есть в домене ecl)"; exit 1; }
  V=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d '{}' "$API/versions/by-codeset/$CS_ID" | jget "['id']")
  resp=$(curl -s -w $'\n%{http_code}' -X POST -H "Authorization: Bearer $T_AUTHOR" \
    -H 'Content-Type: application/json' --data-binary @"$items_file" "$API/versions/$V/items/bulk")
  code="${resp##*$'\n'}"
  [ "$code" = "200" ] || { echo "ОШИБКА bulk ($cs): HTTP $code"; echo "${resp%$'\n'*}" | head -c 800; exit 1; }
  fourEyes "$V"
  VERS[$cs]="$V"
  echo "    [$cs] $n элементов → PUBLISHED"
done

echo ""
echo "==> финальные статусы:"
ok=0
for cs in "${CODESETS[@]}"; do
  STATUS=$(curl -s -H "Authorization: Bearer $T_AUTHOR" "$API/versions/${VERS[$cs]}" | jget "['status']")
  printf "    %-28s %s\n" "$cs" "$STATUS"
  [ "$STATUS" = "PUBLISHED" ] && ok=$((ok+1))
done
echo ""
echo "ГОТОВО: домены ECL+Airfly заведены, 4 OM-учётки созданы, $ok/${#CODESETS[@]} справочников ECL → PUBLISHED под доменом ECL."
