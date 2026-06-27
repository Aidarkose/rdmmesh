#!/usr/bin/env bash
# rdmmesh — демо-данные: справочники ECL / МСФО9 (IFRS 9 ECL).
# Источник — схема ecl__stg проекта ecl_dbt_project (12 таблиц r_ecl_* / r_lnk_* /
# r_coef_*). Фикстуры выгружены в bootstrap/seed/ecl/<name>.{codeset,items}.json и
# здесь заводятся как CodeSet'ы домена 'ecl_<sfx>': для каждого справочника
# создаётся CodeSet (key_spec + JSON-схема из <name>.codeset.json), DRAFT-версия,
# bulk-залив items (<name>.items.json) и полный 4-eyes flow до PUBLISHED.
#
# Крупные коэффициентные матрицы (PD 12k, indv_reserves ~5k, pd_macro ~4k, LGD ~1k
# строк) грузятся одним POST на /items/bulk — лимит import-тела 25 MiB этого хватает.
#
# Идемпотентно по SFX: каждый прогон создаёт свежий домен ecl_<sfx>. Требует
# поднятого стека (make up). Конвенция тегов: ecl, ifrs9, kind:{dimension|mapping|coefficient}.

set -euo pipefail

# Порты переопределяемы через env (если стек поднят не на дефолтных портах
# compose — например, при занятых 8090/8080 docker-compose .env смещает их).
KC_PORT="${KC_PORT:-8090}"
API_PORT="${API_PORT:-8080}"
KC=http://localhost:${KC_PORT}/realms/bank/protocol/openid-connect/token
API=http://localhost:${API_PORT}/api/v1
SFX="${SFX:-$(date +%H%M%S)}"
OMID=$(python3 -c "import uuid;print(uuid.uuid4())")
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../bootstrap/seed/ecl" && pwd)"

# Порядок: сначала dimension-справочники, затем mapping и коэффициенты.
CODESETS=(
  r_ecl_branch_sgmnt
  r_ecl_prdct_sgmnt
  r_ecl_pledge_group
  r_ecl_pledge_group_quality
  r_lnk_branch_to_ecl_sgmnt
  r_lnk_prdct_to_ecl_sgmnt
  r_lnk_pledge_to_ecl_group
  r_coef_pd
  r_coef_pd_macro
  r_coef_lgd
  r_coef_ead_ttd
  r_coef_indv_reserves
)

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

token() {
  curl -s -X POST "$KC" -d grant_type=password -d client_id=rdmmesh-ui \
    -d "username=$1" -d "password=dev" -d scope=openid | jget "['access_token']"
}

# Полный 4-eyes для одной версии: submit → steward_approve → owner_approve (→ auto-publish).
fourEyes() {
  local V="$1"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d "{\"to\":\"IN_REVIEW\",\"comment\":\"готово к ревью\",
         \"assignee\":{\"domain_id\":\"$DOM\",\"steward_om_user_id\":\"$ME_STEWARD\",\"owner_om_user_id\":\"$ME_OWNER\"}}" \
    "$API/versions/$V/transitions"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_STEWARD" -H 'Content-Type: application/json' \
    -d '{"to":"STEWARD_APPROVED","comment":"схема ок"}' "$API/versions/$V/transitions"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_OWNER" -H 'Content-Type: application/json' \
    -d '{"to":"OWNER_APPROVED","comment":"бизнес-аппрув"}' "$API/versions/$V/transitions"
}

echo "==> seed-каталог: $SEED_DIR"
echo "==> получаю токены"
T_ADMIN=$(token dev-admin)
T_AUTHOR=$(token dev-author)
T_STEWARD=$(token dev-steward)
T_OWNER=$(token dev-owner)

echo "==> создаю домен ecl_$SFX (dev-admin)"
DOM=$(curl -s -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"om_domain_id\":\"$OMID\",\"name\":\"ecl_$SFX\",\"display_name\":\"ECL / IFRS 9 ($SFX)\",
       \"label_ru\":\"ECL / МСФО9 ($SFX)\",\"label_en\":\"ECL / IFRS 9 ($SFX)\"}" \
  "$API/domains" | jget "['id']")
echo "    domain id = $DOM"

echo "==> справочник ролей домена — резолвлю om_user_id согласующих (E17)"
ME_STEWARD=$(curl -s -H "Authorization: Bearer $T_STEWARD" "$API/auth/me" | jget "['omUserId']")
ME_OWNER=$(curl -s -H "Authorization: Bearer $T_OWNER" "$API/auth/me" | jget "['omUserId']")
curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"entries\":[
        {\"om_domain_id\":\"$OMID\",\"role\":\"STEWARD\",\"om_user_id\":\"$ME_STEWARD\",\"username\":\"dev-steward\",\"display_name\":\"Dev Steward\"},
        {\"om_domain_id\":\"$OMID\",\"role\":\"BUSINESS_OWNER\",\"om_user_id\":\"$ME_OWNER\",\"username\":\"dev-owner\",\"display_name\":\"Dev Owner\"}
      ]}" \
  "$API/admin/domain-role-directory/reload"

declare -A VERS
for cs in "${CODESETS[@]}"; do
  cs_file="$SEED_DIR/$cs.codeset.json"
  items_file="$SEED_DIR/$cs.items.json"
  [ -f "$cs_file" ]    || { echo "ОШИБКА: нет $cs_file";    exit 1; }
  [ -f "$items_file" ] || { echo "ОШИБКА: нет $items_file"; exit 1; }
  n=$(python3 -c "import json;print(len(json.load(open('$items_file'))))")

  echo "==> [$cs] создаю CodeSet ($n элементов)"
  CS_ID=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    --data-binary @"$cs_file" "$API/codesets/by-domain/$DOM" | jget "['id']")
  [ -n "$CS_ID" ] || { echo "ОШИБКА: пустой codeset id для $cs"; exit 1; }

  V=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d '{}' "$API/versions/by-codeset/$CS_ID" | jget "['id']")
  [ -n "$V" ] || { echo "ОШИБКА: пустой version id для $cs"; exit 1; }

  echo "    bulk-залив items → версия $V"
  resp=$(curl -s -w $'\n%{http_code}' -X POST -H "Authorization: Bearer $T_AUTHOR" \
    -H 'Content-Type: application/json' --data-binary @"$items_file" \
    "$API/versions/$V/items/bulk")
  code="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [ "$code" != "200" ]; then
    echo "ОШИБКА bulk ($cs): HTTP $code"
    echo "$body" | head -c 2000
    exit 1
  fi

  echo "    4-eyes → PUBLISHED"
  fourEyes "$V"
  VERS[$cs]="$V"
done

echo ""
echo "==> финальные статусы:"
ok=0
for cs in "${CODESETS[@]}"; do
  V="${VERS[$cs]}"
  STATUS=$(curl -s -H "Authorization: Bearer $T_AUTHOR" "$API/versions/$V" | jget "['status']")
  printf "    %-28s %s\n" "$cs" "$STATUS"
  [ "$STATUS" = "PUBLISHED" ] && ok=$((ok+1))
done

echo ""
echo "ГОТОВО: $ok/${#CODESETS[@]} справочников PUBLISHED в домене 'ECL / IFRS 9 ($SFX)'."
echo "       Зайдите в UI (dev-author/dev) → домен ECL — увидите 12 CodeSet'ов:"
echo "         • 4 dimension (r_ecl_*), 3 mapping (r_lnk_*), 5 coefficient (r_coef_*)."
