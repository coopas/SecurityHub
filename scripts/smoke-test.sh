#!/usr/bin/env bash
# End-to-end smoke test against a running stack. Exercises the real API with real data:
# nothing here is mocked or seeded behind the scenes.
#
#   ./scripts/smoke-test.sh [api-base-url] [frontend-url]
#
# Defaults match docker-compose.yml.

set -euo pipefail

API="${1:-http://localhost:8080}"
WEB="${2:-http://localhost:8081}"
SUFFIX="$(date +%s)$RANDOM"
PASSWORD="Smoke-Test-$SUFFIX"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Comando obrigatório ausente: $1"; exit 1; }
}
require curl
require jq

# status_of METHOD URL [TOKEN] [BODY]
status_of() {
  local method="$1" url="$2" token="${3:-}" body="${4:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method" "$url")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

# call METHOD URL [TOKEN] [BODY] -> response body, fails on non-2xx
call() {
  local method="$1" url="$2" token="${3:-}" body="${4:-}"
  local args=(-s -w '\n%{http_code}' -X "$method" "$url")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local raw code payload
  raw="$(curl "${args[@]}")"
  code="$(printf '%s' "$raw" | tail -n1)"
  payload="$(printf '%s' "$raw" | sed '$d')"
  if [ "${code:0:1}" != "2" ]; then
    echo "  requisição $method $url devolveu $code" >&2
    echo "  corpo: $payload" >&2
    return 1
  fi
  printf '%s' "$payload"
}

step "1. Saúde dos serviços"
health="$(curl -s "$API/actuator/health" | jq -r '.status' 2>/dev/null || echo DOWN)"
[ "$health" = "UP" ] || fail "backend não está UP (recebido: $health)"
pass "backend respondendo em $API"

web_status="$(curl -s -o /dev/null -w '%{http_code}' "$WEB/")"
[ "$web_status" = "200" ] || fail "frontend devolveu $web_status em $WEB"
pass "frontend servindo em $WEB"

step "2. Endpoint protegido sem token"
code="$(status_of GET "$API/api/v1/projects")"
[ "$code" = "401" ] || fail "esperado 401 sem token, recebido $code"
pass "GET /projects sem token devolve 401"

code="$(status_of GET "$API/api/v1/projects" "token-invalido-qualquer")"
[ "$code" = "401" ] || fail "esperado 401 com token inválido, recebido $code"
pass "token inválido devolve 401"

step "3. Cadastro da empresa A e autenticação"
reg_a="$(call POST "$API/api/v1/auth/register" "" "{
  \"companyName\": \"Smoke A $SUFFIX\",
  \"name\": \"Admin A\",
  \"email\": \"admin-a-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "cadastro da empresa A falhou"
pass "empresa A criada"

login_a="$(call POST "$API/api/v1/auth/login" "" "{
  \"email\": \"admin-a-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "login da empresa A falhou"
TOKEN_A="$(printf '%s' "$login_a" | jq -r '.accessToken')"
USER_A="$(printf '%s' "$login_a" | jq -r '.user.id')"
[ -n "$TOKEN_A" ] && [ "$TOKEN_A" != "null" ] || fail "login não devolveu accessToken"
[ "$(printf '%s' "$login_a" | jq -r '.user.role')" = "ADMIN" ] || fail "primeiro usuário não é ADMIN"
pass "login devolveu access token e papel ADMIN"

me="$(call GET "$API/api/v1/auth/me" "$TOKEN_A")" || fail "/auth/me falhou"
[ "$(printf '%s' "$me" | jq -r '.id')" = "$USER_A" ] || fail "/auth/me devolveu outro usuário"
pass "/auth/me coerente com o token"

step "4. Fluxo de domínio na empresa A"
project="$(call POST "$API/api/v1/projects" "$TOKEN_A" "{
  \"name\": \"Projeto Smoke $SUFFIX\",
  \"description\": \"Criado pelo smoke test\"
}")" || fail "criação de projeto falhou"
PROJECT_ID="$(printf '%s' "$project" | jq -r '.id')"
pass "projeto $PROJECT_ID criado"

asset="$(call POST "$API/api/v1/assets" "$TOKEN_A" "{
  \"projectId\": $PROJECT_ID,
  \"name\": \"API de pagamentos\",
  \"type\": \"API\",
  \"identifier\": \"api-$SUFFIX.smoke.test\",
  \"environment\": \"PRODUCTION\",
  \"criticality\": \"HIGH\"
}")" || fail "criação de ativo falhou"
ASSET_ID="$(printf '%s' "$asset" | jq -r '.id')"
pass "ativo $ASSET_ID criado"

vuln="$(call POST "$API/api/v1/vulnerabilities" "$TOKEN_A" "{
  \"assetId\": $ASSET_ID,
  \"title\": \"SQL injection no endpoint de busca\",
  \"description\": \"Parâmetro concatenado diretamente na query\",
  \"severity\": \"CRITICAL\",
  \"cvssScore\": 9.1,
  \"cve\": \"CVE-2024-12345\",
  \"discoveredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
}")" || fail "criação de vulnerabilidade falhou"
VULN_ID="$(printf '%s' "$vuln" | jq -r '.id')"
[ "$(printf '%s' "$vuln" | jq -r '.status')" = "OPEN" ] || fail "vulnerabilidade não nasceu OPEN"
pass "vulnerabilidade $VULN_ID criada com status OPEN"

assigned="$(call PATCH "$API/api/v1/vulnerabilities/$VULN_ID/assignee" "$TOKEN_A" \
  "{\"userId\": $USER_A}")" || fail "atribuição falhou"
[ "$(printf '%s' "$assigned" | jq -r '.assignedTo.id')" = "$USER_A" ] || fail "responsável não foi gravado"
pass "vulnerabilidade atribuída"

progress="$(call PATCH "$API/api/v1/vulnerabilities/$VULN_ID/status" "$TOKEN_A" \
  "{\"status\": \"IN_PROGRESS\"}")" || fail "mudança para IN_PROGRESS falhou"
[ "$(printf '%s' "$progress" | jq -r '.status')" = "IN_PROGRESS" ] || fail "status não mudou"
pass "status alterado para IN_PROGRESS"

comment="$(call POST "$API/api/v1/vulnerabilities/$VULN_ID/comments" "$TOKEN_A" \
  "{\"content\": \"Correção iniciada pelo smoke test\"}")" || fail "comentário falhou"
[ "$(printf '%s' "$comment" | jq -r '.content')" != "null" ] || fail "comentário vazio"
pass "comentário registrado"

resolved="$(call PATCH "$API/api/v1/vulnerabilities/$VULN_ID/status" "$TOKEN_A" \
  "{\"status\": \"RESOLVED\"}")" || fail "mudança para RESOLVED falhou"
[ "$(printf '%s' "$resolved" | jq -r '.status')" = "RESOLVED" ] || fail "status final incorreto"
[ "$(printf '%s' "$resolved" | jq -r '.resolvedAt')" != "null" ] || fail "resolvedAt não foi preenchido"
pass "status RESOLVED preencheu resolvedAt"

step "5. Auditoria"
audit="$(call GET "$API/api/v1/audit-logs?size=50" "$TOKEN_A")" || fail "consulta de auditoria falhou"
audit_count="$(printf '%s' "$audit" | jq '.content | length')"
[ "$audit_count" -gt 0 ] || fail "trilha de auditoria vazia"
printf '%s' "$audit" | jq -e '.content[] | select(.action == "STATUS_CHANGE")' >/dev/null \
  || fail "mudança de status não foi auditada"
printf '%s' "$audit" | grep -qiE '"(password|passwordHash|senha|token|secret)"[[:space:]]*:[[:space:]]*"[^*]' \
  && fail "auditoria contém um campo sensível não mascarado"
pass "auditoria registrou $audit_count eventos, sem campos sensíveis"

step "6. Dashboard"
summary="$(call GET "$API/api/v1/dashboard/summary" "$TOKEN_A")" || fail "dashboard/summary falhou"
[ "$(printf '%s' "$summary" | jq -r '.totalVulnerabilities')" -ge 1 ] || fail "summary não contou a vulnerabilidade"
[ "$(printf '%s' "$summary" | jq -r '.totalProjects')" -ge 1 ] || fail "summary não contou o projeto"
[ "$(printf '%s' "$summary" | jq -r '.totalAssets')" -ge 1 ] || fail "summary não contou o ativo"
pass "dashboard reflete os dados criados"

call GET "$API/api/v1/dashboard/severity-distribution" "$TOKEN_A" >/dev/null || fail "severity-distribution falhou"
call GET "$API/api/v1/dashboard/status-distribution" "$TOKEN_A" >/dev/null || fail "status-distribution falhou"
call GET "$API/api/v1/dashboard/trend?days=30" "$TOKEN_A" >/dev/null || fail "trend falhou"
pass "demais endpoints do dashboard respondem"

step "7. Isolamento entre empresas"
call POST "$API/api/v1/auth/register" "" "{
  \"companyName\": \"Smoke B $SUFFIX\",
  \"name\": \"Admin B\",
  \"email\": \"admin-b-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}" >/dev/null || fail "cadastro da empresa B falhou"

login_b="$(call POST "$API/api/v1/auth/login" "" "{
  \"email\": \"admin-b-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "login da empresa B falhou"
TOKEN_B="$(printf '%s' "$login_b" | jq -r '.accessToken')"
pass "empresa B criada e autenticada"

for pair in "projects:$PROJECT_ID" "assets:$ASSET_ID" "vulnerabilities:$VULN_ID"; do
  resource="${pair%%:*}"; id="${pair##*:}"
  code="$(status_of GET "$API/api/v1/$resource/$id" "$TOKEN_B")"
  [ "$code" = "404" ] || fail "empresa B leu /$resource/$id da empresa A (HTTP $code, esperado 404)"
  code="$(status_of DELETE "$API/api/v1/$resource/$id" "$TOKEN_B")"
  [ "$code" = "404" ] || fail "empresa B apagou /$resource/$id da empresa A (HTTP $code, esperado 404)"
done
pass "empresa B recebe 404 em todos os recursos da empresa A"

list_b="$(call GET "$API/api/v1/projects?size=100" "$TOKEN_B")" || fail "listagem da empresa B falhou"
printf '%s' "$list_b" | jq -e ".content[] | select(.id == $PROJECT_ID)" >/dev/null \
  && fail "listagem da empresa B expôs o projeto da empresa A"
pass "listagens da empresa B não contêm dados da empresa A"

audit_b="$(call GET "$API/api/v1/audit-logs?size=100" "$TOKEN_B")" || fail "auditoria da empresa B falhou"
printf '%s' "$audit_b" | jq -e ".content[] | select(.actorId == $USER_A)" >/dev/null \
  && fail "auditoria da empresa B expôs eventos da empresa A"
pass "auditoria isolada entre empresas"

step "8. Recurso ainda existe para a empresa A"
call GET "$API/api/v1/vulnerabilities/$VULN_ID" "$TOKEN_A" >/dev/null \
  || fail "empresa A perdeu acesso ao próprio recurso"
pass "empresa A continua enxergando os próprios dados"

printf '\n\033[32mSmoke test concluído com sucesso.\033[0m\n'
