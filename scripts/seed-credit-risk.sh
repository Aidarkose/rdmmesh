#!/usr/bin/env bash
# rdmmesh — демо-данные для E19 (Credit Risk Matrices). Создаёт домен
# 'credit_risk_<sfx>' и три CodeSet'а: rating_scale (5 грейдов AAA/A/BB/B/D),
# delinquency_buckets (0-30 / 30-90 / 90+), rating_transition_matrix (5×5
# для горизонта 1Y — пример заказчика P с дописанной D-колонкой из невязок).
# Каждый прогоняется через полный 4-eyes flow до PUBLISHED. Идемпотентно по SFX.
#
# E19 Slice C — Commit 1 (foundation): pivot-вью UI и pivot-импорт XLSX
# добавляются в commits 2 и 3 поверх этих данных.
#
# Конвенция для UI-pivot-вью: CodeSet помечается tag-ом 'kind:transition_matrix'
# (см. docs/handoff/E19-credit-risk-matrices.md §2.3 — осознанный выбор для
# Slice C вместо отдельной колонки code_set.kind).

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

# Полный 4-eyes для одной версии: submit → steward_approve → owner_approve (→ auto-publish).
# Использует адресную маршрутизацию E17 (BR-21).
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

echo "==> получаю токены"
T_ADMIN=$(token dev-admin)
T_AUTHOR=$(token dev-author)
T_STEWARD=$(token dev-steward)
T_OWNER=$(token dev-owner)

echo "==> создаю домен credit_risk_$SFX (dev-admin, bootstrap E18)"
DOM=$(curl -s -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"om_domain_id\":\"$OMID\",\"name\":\"credit_risk_$SFX\",\"display_name\":\"Credit Risk ($SFX)\",
       \"label_ru\":\"Кредитный риск ($SFX)\",\"label_en\":\"Credit Risk ($SFX)\"}" \
  "$API/domains" | jget "['id']")
echo "    domain id = $DOM"

echo "==> E17: справочник ролей домена — резолвлю om_user_id согласующих"
ME_STEWARD=$(curl -s -H "Authorization: Bearer $T_STEWARD" "$API/auth/me" | jget "['omUserId']")
ME_OWNER=$(curl -s -H "Authorization: Bearer $T_OWNER" "$API/auth/me" | jget "['omUserId']")
echo "    steward om_user_id = $ME_STEWARD"
echo "    owner   om_user_id = $ME_OWNER"

echo "==> reload справочника ролей домена (TRUNCATE+INSERT — затирает прежний сид)"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
  -d "{\"entries\":[
        {\"om_domain_id\":\"$OMID\",\"role\":\"STEWARD\",\"om_user_id\":\"$ME_STEWARD\",\"username\":\"dev-steward\",\"display_name\":\"Dev Steward\"},
        {\"om_domain_id\":\"$OMID\",\"role\":\"BUSINESS_OWNER\",\"om_user_id\":\"$ME_OWNER\",\"username\":\"dev-owner\",\"display_name\":\"Dev Owner\"}
      ]}" \
  "$API/admin/domain-role-directory/reload"

# ── 1. rating_scale ─────────────────────────────────────────────────────────
echo "==> создаю CodeSet rating_scale_$SFX (key=code, tag=kind:rating_scale)"
CS_SCALE=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d "{\"name\":\"rating_scale_$SFX\",\"display_name\":\"Rating Scale ($SFX)\",
       \"label_ru\":\"Шкала рейтингов\",\"label_en\":\"Rating scale\",
       \"tags\":[\"kind:rating_scale\",\"credit_risk\"],
       \"hierarchy_mode\":\"NONE\",
       \"key_spec\":{\"parts\":[{\"name\":\"code\",\"type\":\"STRING\"}]},
       \"initial_schema\":{\"type\":\"object\",\"required\":[\"order\",\"is_absorbing\"],
         \"properties\":{
           \"order\":{\"type\":\"integer\",\"minimum\":1},
           \"is_absorbing\":{\"type\":\"boolean\"},
           \"description\":{\"type\":\"string\"}
         }}}" \
  "$API/codesets/by-domain/$DOM" | jget "['id']")
V_SCALE=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d '{}' "$API/versions/by-codeset/$CS_SCALE" | jget "['id']")

echo "==> наполняю rating_scale: AAA, A, BB, B, D (absorbing)"
add_scale_item() {
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d "{\"key_parts\":[\"$1\"],\"label_ru\":\"$2\",\"label_en\":\"$3\",
         \"attributes\":{\"order\":$4,\"is_absorbing\":$5,\"description\":\"$6\"}}" \
    "$API/versions/$V_SCALE/items"
}
add_scale_item AAA "Высший"   "Highest" 1 false "Investment grade — лучший"
add_scale_item A   "Высокий"  "High"    2 false "Investment grade"
add_scale_item BB  "Средний"  "Medium"  3 false "Speculative"
add_scale_item B   "Низкий"   "Low"     4 false "Highly speculative"
add_scale_item D   "Дефолт"   "Default" 5 true  "Absorbing state"

echo "==> 4-eyes для rating_scale → PUBLISHED"
fourEyes "$V_SCALE"

# ── 2. delinquency_buckets ──────────────────────────────────────────────────
echo "==> создаю CodeSet delinquency_buckets_$SFX (key=bucket_code, tag=kind:delinquency_buckets)"
CS_BUCK=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d "{\"name\":\"delinquency_buckets_$SFX\",\"display_name\":\"Delinquency Buckets ($SFX)\",
       \"label_ru\":\"Корзины просрочки\",\"label_en\":\"Delinquency buckets\",
       \"tags\":[\"kind:delinquency_buckets\",\"credit_risk\"],
       \"hierarchy_mode\":\"NONE\",
       \"key_spec\":{\"parts\":[{\"name\":\"bucket_code\",\"type\":\"STRING\"}]},
       \"initial_schema\":{\"type\":\"object\",\"required\":[\"days_from\",\"is_default\",\"sicr_stage\"],
         \"properties\":{
           \"days_from\":{\"type\":\"integer\",\"minimum\":0},
           \"days_to\":{\"type\":[\"integer\",\"null\"],\"minimum\":0},
           \"is_default\":{\"type\":\"boolean\"},
           \"sicr_stage\":{\"type\":\"string\",\"enum\":[\"SICR_1\",\"SICR_2\",\"SICR_3\"]}
         }}}" \
  "$API/codesets/by-domain/$DOM" | jget "['id']")
V_BUCK=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d '{}' "$API/versions/by-codeset/$CS_BUCK" | jget "['id']")

echo "==> наполняю delinquency_buckets: 0-30 / 30-90 / 90+ (default)"
add_bucket() {
  local code="$1" df="$2" dt="$3" def="$4" stage="$5" lru="$6" len="$7"
  local dt_json
  if [ "$dt" = "null" ]; then dt_json="null"; else dt_json="$dt"; fi
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d "{\"key_parts\":[\"$code\"],\"label_ru\":\"$lru\",\"label_en\":\"$len\",
         \"attributes\":{\"days_from\":$df,\"days_to\":$dt_json,\"is_default\":$def,\"sicr_stage\":\"$stage\"}}" \
    "$API/versions/$V_BUCK/items"
}
add_bucket BUCKET_0_30    0   30   false SICR_1 "Без просрочки или до 30 дней" "Up to 30 days past due"
add_bucket BUCKET_30_90   30  90   false SICR_2 "Просрочка 30-90 дней"          "30-90 days past due"
add_bucket BUCKET_90_PLUS 90  null true  SICR_3 "Просрочка свыше 90 дней (дефолт)" "Over 90 days past due (default)"

echo "==> 4-eyes для delinquency_buckets → PUBLISHED"
fourEyes "$V_BUCK"

# ── 3. rating_transition_matrix ─────────────────────────────────────────────
echo "==> создаю CodeSet rating_transition_matrix_$SFX (composite key, tag=kind:transition_matrix)"
CS_TM=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d "{\"name\":\"rating_transition_matrix_$SFX\",\"display_name\":\"Rating Transition Matrix ($SFX)\",
       \"label_ru\":\"Матрица миграций рейтингов\",\"label_en\":\"Rating transition matrix\",
       \"tags\":[\"kind:transition_matrix\",\"credit_risk\"],
       \"hierarchy_mode\":\"NONE\",
       \"key_spec\":{\"parts\":[
         {\"name\":\"from_rating\",\"type\":\"STRING\",\"label\":{\"ru\":\"Из\",\"en\":\"From\"}},
         {\"name\":\"to_rating\",\"type\":\"STRING\",\"label\":{\"ru\":\"В\",\"en\":\"To\"}},
         {\"name\":\"horizon\",\"type\":\"ENUM\",\"label\":{\"ru\":\"Горизонт\",\"en\":\"Horizon\"},
          \"allowed_values\":[\"1M\",\"3M\",\"6M\",\"1Y\",\"3Y\",\"5Y\"]}
       ]},
       \"initial_schema\":{\"type\":\"object\",\"required\":[\"probability\"],
         \"properties\":{
           \"probability\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}
         }}}" \
  "$API/codesets/by-domain/$DOM" | jget "['id']")
V_TM=$(curl -s -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
  -d '{}' "$API/versions/by-codeset/$CS_TM" | jget "['id']")
echo "    transition_matrix version = $V_TM"

echo "==> заливаю 5×5 матрицу для horizon=1Y (пример заказчика P + дописанная D-колонка)"
# Матрица заказчика:
#        AAA      A       BB      B
#  AAA  0.880   0.112   0.006   0.001       (Σ=0.999, невязка 0.001 → D)
#  A    0.023   0.865   0.095   0.015       (Σ=0.998, невязка 0.002 → D)
#  BB   0.001   0.050   0.802   0.127       (Σ=0.980, невязка 0.020 → D)
#  B    0.000   0.005   0.185   0.720       (Σ=0.910, невязка 0.090 → D)
#  D    absorbing: D→D=1, прочее=0
RATINGS="AAA A BB B D"
declare -A P
P[AAA,AAA]=0.880;  P[AAA,A]=0.112;  P[AAA,BB]=0.006;  P[AAA,B]=0.001;  P[AAA,D]=0.001
P[A,AAA]=0.023;    P[A,A]=0.865;    P[A,BB]=0.095;    P[A,B]=0.015;    P[A,D]=0.002
P[BB,AAA]=0.001;   P[BB,A]=0.050;   P[BB,BB]=0.802;   P[BB,B]=0.127;   P[BB,D]=0.020
P[B,AAA]=0.000;    P[B,A]=0.005;    P[B,BB]=0.185;    P[B,B]=0.720;    P[B,D]=0.090
P[D,AAA]=0.000;    P[D,A]=0.000;    P[D,BB]=0.000;    P[D,B]=0.000;    P[D,D]=1.000

for from in $RATINGS; do
  for to in $RATINGS; do
    p="${P[$from,$to]}"
    curl -s -o /dev/null -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
      -d "{\"key_parts\":[\"$from\",\"$to\",\"1Y\"],
           \"label_ru\":\"$from → $to (1Y)\",\"label_en\":\"$from → $to (1Y)\",
           \"attributes\":{\"probability\":$p}}" \
      "$API/versions/$V_TM/items"
  done
done

echo "==> 4-eyes для rating_transition_matrix → PUBLISHED"
fourEyes "$V_TM"

echo "==> финальные статусы:"
for V in "$V_SCALE" "$V_BUCK" "$V_TM"; do
  STATUS=$(curl -s -H "Authorization: Bearer $T_AUTHOR" "$API/versions/$V" | jget "['status']")
  echo "    $V → $STATUS"
done

echo ""
echo "ГОТОВО. В UI зайдите как dev-author/dev (или любой другой), откройте домен"
echo "       'Credit Risk ($SFX)' — увидите три CodeSet'а:"
echo "         • rating_scale_$SFX           — шкала рейтингов (5 грейдов)"
echo "         • delinquency_buckets_$SFX    — корзины просрочки"
echo "         • rating_transition_matrix_$SFX — матрица миграций (25 triples для 1Y)"
echo ""
echo "       Сейчас матрица отрендерится как плоский список 25 строк. Pivot-вью"
echo "       (5×5 квадратной таблицей) будет добавлено в Commit 2 эпика E19."
