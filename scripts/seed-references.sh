#!/usr/bin/env bash
# rdmmesh — засев cross-codeset FK-связей (PUT /codesets/{id}/references).
#
# Прописывает связи между колонками справочников доменов r_product / r_branch /
# r_pledge / r_coefs, перечисленные заказчиком. Связи публикуются в OpenMetadata как
# FOREIGN_KEY — в каталоге БД видна связь колонок, справочники соединяются и видны
# рядом друг с другом.
#
# Идемпотентно: PUT заменяет весь набор связей целевого справочника. Имена резолвятся
# в id через REST; если домена/справочника/целевой колонки нет в стенде — связь
# пропускается с предупреждением (стенд может содержать не все справочники).
#
# Требует поднятый rdmmesh-app (8080) + Keycloak (8090) и роль Author/Admin.
# Usage:  ./scripts/seed-references.sh           (логин dev-author/dev по умолчанию)
#         USER=dev-admin ./scripts/seed-references.sh

set -euo pipefail

KC=http://localhost:8090/realms/bank/protocol/openid-connect/token
API=http://localhost:8080/api/v1
LOGIN_USER="${USER:-dev-author}"
LOGIN_PASS="${PASS:-dev}"

jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

echo "==> получаю токен ($LOGIN_USER)"
TOKEN=$(curl -s -X POST "$KC" -d grant_type=password -d client_id=rdmmesh-ui \
  -d "username=$LOGIN_USER" -d "password=$LOGIN_PASS" -d scope=openid | jget "['access_token']")
AUTH=(-H "Authorization: Bearer $TOKEN")

# domain_name -> domain_id (один GET /domains, кэш в ассоц-массиве).
declare -A DOMAIN_ID
while IFS=$'\t' read -r dname did; do
  DOMAIN_ID["$dname"]="$did"
done < <(curl -s "${AUTH[@]}" "$API/domains" \
  | python3 -c "import sys,json;[print(d['name']+'\t'+d['id']) for d in json.load(sys.stdin)]")

# (domain_name, codeset_name) -> codeset_id. Ленивый резолв с кэшем.
declare -A CODESET_ID
resolve_cs() {  # $1=domain $2=codeset  -> echoes id или пусто
  local dom="$1" cs="$2" key="$1/$2" did
  if [[ -n "${CODESET_ID[$key]:-}" ]]; then echo "${CODESET_ID[$key]}"; return; fi
  did="${DOMAIN_ID[$dom]:-}"
  if [[ -z "$did" ]]; then echo ""; return; fi
  local id
  id=$(curl -s "${AUTH[@]}" "$API/codesets/by-domain/$did" \
    | python3 -c "import sys,json;m={c['name']:c['id'] for c in json.load(sys.stdin)};print(m.get('$cs',''))")
  CODESET_ID[$key]="$id"
  echo "$id"
}

# Одна связь: from_column в JSON для уже определённого from-справочника.
# Группируем по from-справочнику, т.к. PUT заменяет весь набор связей.
# Формат строки EDGES: from_domain|from_codeset|from_column|to_domain|to_codeset|to_column
EDGES=(
  "r_product|r_lnk_prdct_to_ecl_sgmnt|product_sgmnt_id|r_product|r_ecl_prdct_sgmnt|id"
  "r_branch|r_lnk_branch_to_ecl_sgmnt|branch_sgmnt_id|r_branch|r_ecl_branch_sgmnt|id"
  "r_pledge|r_lnk_pledge_to_ecl_group|pledge_group_id|r_pledge|r_ecl_pledge_group|id"
  "r_pledge|r_ecl_pledge_group_quality|pledge_group_id|r_pledge|r_ecl_pledge_group|id"
  "r_coefs|r_coef_pd|product_id|r_product|r_ecl_prdct_sgmnt|id"
  "r_coefs|r_coef_pd|branch_id|r_branch|r_ecl_branch_sgmnt|id"
)

# Собираем from-справочники в порядке появления + JSON-фрагменты связей под каждый.
declare -A REFS_JSON     # from_key -> "obj,obj,..."
declare -a FROM_ORDER
for e in "${EDGES[@]}"; do
  IFS='|' read -r fdom fcs fcol tdom tcs tcol <<<"$e"
  fkey="$fdom/$fcs"
  if [[ -z "${REFS_JSON[$fkey]+x}" ]]; then FROM_ORDER+=("$fkey"); REFS_JSON[$fkey]=""; fi
  tid=$(resolve_cs "$tdom" "$tcs")
  if [[ -z "$tid" ]]; then
    echo "    [skip] цель не найдена: $tdom.$tcs (для $fkey.$fcol)"
    continue
  fi
  obj="{\"from_column\":\"$fcol\",\"to_codeset_id\":\"$tid\",\"to_column\":\"$tcol\"}"
  if [[ -n "${REFS_JSON[$fkey]}" ]]; then REFS_JSON[$fkey]+=","; fi
  REFS_JSON[$fkey]+="$obj"
done

echo "==> применяю связи (PUT /codesets/{id}/references)"
for fkey in "${FROM_ORDER[@]}"; do
  IFS='/' read -r fdom fcs <<<"$fkey"
  fid=$(resolve_cs "$fdom" "$fcs")
  if [[ -z "$fid" ]]; then
    echo "    [skip] справочник-источник не найден: $fkey"
    continue
  fi
  body="{\"references\":[${REFS_JSON[$fkey]}]}"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${AUTH[@]}" \
    -H 'Content-Type: application/json' -d "$body" "$API/codesets/$fid/references")
  if [[ "$code" == "200" ]]; then
    echo "    [ok]   $fkey ← ${REFS_JSON[$fkey]}"
  else
    echo "    [FAIL] $fkey → HTTP $code"
  fi
done

echo ""
echo "ГОТОВО. Запустите ingestion om-rdmmesh-source — связи появятся в OpenMetadata"
echo "как FOREIGN_KEY на таблицах справочников (вкладка таблицы → Schema / Constraints)."
