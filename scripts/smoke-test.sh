#!/usr/bin/env bash
# End-to-end smoke test against a running stack. Exercises the real API with real data:
# nothing here is mocked or seeded behind the scenes.
#
#   ./scripts/smoke-test.sh [api-base-url] [frontend-url] [management-url]
#
# Defaults match docker-compose.yml. A porta de gestão é separada da porta da aplicação — o
# actuator não é mais servido em $API — e vem como terceiro argumento para que o script também
# rode contra uma pilha que não seja a do compose.

set -euo pipefail

API="${1:-http://localhost:8080}"
WEB="${2:-http://localhost:8081}"
MGMT="${3:-http://localhost:9090}"
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
health="$(curl -s "$MGMT/actuator/health" | jq -r '.status' 2>/dev/null || echo DOWN)"
[ "$health" = "UP" ] || fail "backend não está UP (recebido: $health, via $MGMT)"
pass "backend respondendo, saúde em $MGMT"

# A saúde tem de continuar pública: o contexto filho da porta de gestão herda a cadeia de
# segurança da aplicação, e sem o matcher de /actuator/health o healthcheck do contêiner
# responderia 401 e a pilha nunca subiria.
code="$(status_of GET "$MGMT/actuator/health")"
[ "$code" = "200" ] || fail "saúde na porta de gestão devolveu $code sem token (esperado 200)"
pass "a sonda de saúde não depende de autenticação"

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

# O vazamento que a mudança fechou. /actuator/metrics não estava em PUBLIC_ENDPOINTS, caía em
# anyRequest().authenticated() e portanto era legível por qualquer usuário autenticado da
# aplicação — inclusive um VIEWER. Agora não existe handler nenhum nesta porta: com token
# válido a resposta é 404, e é por isso que a verificação usa um token.
for path in /actuator/metrics /actuator/prometheus /actuator/health /actuator/info; do
  code="$(status_of GET "$API$path" "$TOKEN_A")"
  [ "$code" = "404" ] || fail "$path responde $code na porta da aplicação para um autenticado (esperado 404)"
done
pass "nenhum endpoint do actuator é servido na porta da aplicação, nem para quem tem token"

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

step "9. Renovação e encerramento de sessão"
REFRESH_A="$(printf '%s' "$login_a" | jq -r '.refreshToken')"
[ -n "$REFRESH_A" ] && [ "$REFRESH_A" != "null" ] || fail "login não devolveu refreshToken"

rotated="$(call POST "$API/api/v1/auth/refresh" "" "{\"refreshToken\": \"$REFRESH_A\"}")" \
  || fail "refresh falhou"
ROTATED_TOKEN="$(printf '%s' "$rotated" | jq -r '.accessToken')"
ROTATED_REFRESH="$(printf '%s' "$rotated" | jq -r '.refreshToken')"
[ "$ROTATED_REFRESH" != "$REFRESH_A" ] || fail "refresh devolveu o mesmo token (não houve rotação)"
call GET "$API/api/v1/auth/me" "$ROTATED_TOKEN" >/dev/null || fail "token renovado não é aceito"
pass "refresh rotaciona o token e o novo access token vale"

# Reapresentar o token recém-rotacionado dentro da janela de tolerância (REUSE_GRACE, 30s)
# devolve 200 de propósito: duas abas, um retry de rede ou um timeout fazem o mesmo token
# chegar duas vezes em segundos, e sem a janela a detecção de reuso deslogaria o usuário
# legítimo em toda corrida benigna. Fora da janela a família inteira cai — isso é coberto por
# RefreshTokenIntegrationTest, que controla o relógio; aqui o que se verifica é que o caminho
# benigno não derruba ninguém.
code="$(status_of POST "$API/api/v1/auth/refresh" "" "{\"refreshToken\": \"$REFRESH_A\"}")"
[ "$code" = "200" ] || fail "reuso dentro da janela de tolerância devolveu $code, esperado 200"
pass "reuso imediato cai na janela de tolerância e não desloga o usuário"

# Cada login abre uma sessão nova, então esta não afeta o TOKEN_A usado acima.
login_logout="$(call POST "$API/api/v1/auth/login" "" "{
  \"email\": \"admin-a-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "login para o teste de logout falhou"
LOGOUT_REFRESH="$(printf '%s' "$login_logout" | jq -r '.refreshToken')"
code="$(status_of POST "$API/api/v1/auth/logout" "" "{\"refreshToken\": \"$LOGOUT_REFRESH\"}")"
[ "$code" = "204" ] || fail "logout devolveu $code, esperado 204"
code="$(status_of POST "$API/api/v1/auth/refresh" "" "{\"refreshToken\": \"$LOGOUT_REFRESH\"}")"
[ "$code" = "401" ] || fail "refresh após logout devolveu $code, esperado 401"
pass "logout revoga a sessão no servidor"

step "10. Recuperação de senha"
# 202 nos dois casos: uma resposta diferente para e-mail desconhecido seria enumeração de contas.
code="$(status_of POST "$API/api/v1/auth/password-reset/request" "" \
  "{\"email\": \"admin-a-$SUFFIX@smoke.test\"}")"
[ "$code" = "202" ] || fail "solicitação de recuperação devolveu $code, esperado 202"
code="$(status_of POST "$API/api/v1/auth/password-reset/request" "" \
  "{\"email\": \"nao-existe-$SUFFIX@smoke.test\"}")"
[ "$code" = "202" ] || fail "e-mail desconhecido devolveu $code — resposta distinta permite enumerar contas"
pass "solicitação responde 202 para e-mail conhecido e desconhecido"

code="$(status_of POST "$API/api/v1/auth/password-reset/confirm" "" \
  "{\"token\": \"token-que-nunca-existiu\", \"password\": \"$PASSWORD-novo\"}")"
[ "$code" = "400" ] || fail "confirmação com token inválido devolveu $code, esperado 400"
pass "confirmação com token inválido é recusada"

step "11. Convites"
invitation="$(call POST "$API/api/v1/invitations" "$TOKEN_A" "{
  \"name\": \"Convidado Smoke\",
  \"email\": \"convidado-$SUFFIX@smoke.test\",
  \"role\": \"ANALYST\"
}")" || fail "criação de convite falhou"
INVITATION_ID="$(printf '%s' "$invitation" | jq -r '.id')"
[ "$(printf '%s' "$invitation" | jq -r '.status')" = "PENDING" ] || fail "convite não nasceu PENDING"
# O token do convite nunca volta pela API: devolvê-lo permitiria assumir a conta do convidado.
printf '%s' "$invitation" | jq -e 'has("token") or has("tokenHash")' >/dev/null \
  && fail "a resposta do convite carrega o token"
pass "convite $INVITATION_ID criado, sem expor o token"

invitations="$(call GET "$API/api/v1/invitations" "$TOKEN_A")" || fail "listagem de convites falhou"
printf '%s' "$invitations" | jq -e ".[] | select(.id == $INVITATION_ID)" >/dev/null \
  || fail "convite criado não aparece na listagem"
pass "convite aparece na listagem da empresa"

code="$(status_of GET "$API/api/v1/invitations/accept?token=token-que-nunca-existiu")"
[ "$code" = "400" ] || fail "prévia com token inválido devolveu $code, esperado 400"
pass "prévia pública recusa um token inválido"

code="$(status_of DELETE "$API/api/v1/invitations/$INVITATION_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "revogação devolveu $code, esperado 204"
pass "convite revogado"

step "12. Administração de usuários"
users="$(call GET "$API/api/v1/users" "$TOKEN_A")" || fail "listagem de usuários falhou"
printf '%s' "$users" | jq -e ".[] | select(.id == $USER_A)" >/dev/null \
  || fail "o próprio administrador não aparece na listagem"
pass "listagem de usuários responde"

self="$(call GET "$API/api/v1/users/$USER_A" "$TOKEN_A")" || fail "GET /users/{id} falhou"
[ "$(printf '%s' "$self" | jq -r '.email')" = "admin-a-$SUFFIX@smoke.test" ] \
  || fail "GET /users/{id} devolveu outro usuário"
pass "GET /users/{id} coerente"

renamed="$(call PATCH "$API/api/v1/users/$USER_A" "$TOKEN_A" "{\"name\": \"Admin A Renomeado\"}")" \
  || fail "PATCH /users/{id} falhou"
[ "$(printf '%s' "$renamed" | jq -r '.name')" = "Admin A Renomeado" ] || fail "nome não foi gravado"
pass "PATCH /users/{id} altera o nome"

# A empresa A tem exatamente um administrador — o próprio chamador. Rebaixá-lo ou desativá-lo
# deixaria a empresa sem ninguém capaz de administrá-la, e o backend recusa com 409.
code="$(status_of PATCH "$API/api/v1/users/$USER_A/role" "$TOKEN_A" "{\"role\": \"VIEWER\"}")"
[ "$code" = "409" ] || fail "rebaixar o último ADMIN devolveu $code, esperado 409"
code="$(status_of PATCH "$API/api/v1/users/$USER_A/active" "$TOKEN_A" "{\"active\": false}")"
[ "$code" = "409" ] || fail "desativar o último ADMIN devolveu $code, esperado 409"
pass "o último administrador ativo não pode se rebaixar nem se desativar"

step "13. Exportação CSV"
csv="$(curl -s -H "Authorization: Bearer $TOKEN_A" "$API/api/v1/vulnerabilities/export")"
printf '%s' "$csv" | head -n1 | grep -q 'severidade' || fail "CSV sem a linha de cabeçalho esperada"
printf '%s' "$csv" | grep -q "SQL injection no endpoint de busca" \
  || fail "CSV não contém a vulnerabilidade criada"
pass "exportação devolve cabeçalho e a linha criada"

filtered="$(curl -s -H "Authorization: Bearer $TOKEN_A" \
  "$API/api/v1/vulnerabilities/export?severity=LOW")"
printf '%s' "$filtered" | grep -q "SQL injection no endpoint de busca" \
  && fail "filtro severity=LOW devolveu uma vulnerabilidade CRITICAL"
pass "o filtro da exportação é o mesmo da listagem"

code="$(status_of GET "$API/api/v1/vulnerabilities/export" "$TOKEN_B")"
[ "$code" = "200" ] || fail "exportação da empresa B devolveu $code"
other="$(curl -s -H "Authorization: Bearer $TOKEN_B" "$API/api/v1/vulnerabilities/export")"
printf '%s' "$other" | grep -q "SQL injection no endpoint de busca" \
  && fail "a exportação da empresa B contém dados da empresa A"
pass "exportação isolada entre empresas"

step "14. Anexos"
EVIDENCE="$(mktemp -t smoke-evidencia-XXXXXX.pdf)"
DOWNLOADED="$(mktemp -t smoke-download-XXXXXX.pdf)"
REPORT="$(mktemp -t smoke-relatorio-XXXXXX.pdf)"
trap 'rm -f "$EVIDENCE" "$DOWNLOADED" "$REPORT"' EXIT
printf '%%PDF-1.7\nevidencia do smoke test\n%%%%EOF\n' > "$EVIDENCE"

attachment="$(curl -s -H "Authorization: Bearer $TOKEN_A" -F "file=@$EVIDENCE" \
  "$API/api/v1/vulnerabilities/$VULN_ID/attachments")"
ATTACHMENT_ID="$(printf '%s' "$attachment" | jq -r '.id')"
[ -n "$ATTACHMENT_ID" ] && [ "$ATTACHMENT_ID" != "null" ] || fail "upload de anexo falhou: $attachment"
[ "$(printf '%s' "$attachment" | jq -r '.contentType')" = "application/pdf" ] \
  || fail "o tipo do anexo não foi detectado pelos bytes"
pass "anexo $ATTACHMENT_ID enviado e reconhecido como application/pdf"

listed="$(call GET "$API/api/v1/vulnerabilities/$VULN_ID/attachments" "$TOKEN_A")" \
  || fail "listagem de anexos falhou"
printf '%s' "$listed" | jq -e ".[] | select(.id == $ATTACHMENT_ID)" >/dev/null \
  || fail "anexo não aparece na listagem"
pass "anexo aparece na listagem da vulnerabilidade"

curl -s -H "Authorization: Bearer $TOKEN_A" -o "$DOWNLOADED" \
  "$API/api/v1/vulnerabilities/$VULN_ID/attachments/$ATTACHMENT_ID/download"
cmp -s "$EVIDENCE" "$DOWNLOADED" || fail "o arquivo baixado difere do enviado"
pass "download devolve exatamente os bytes enviados"

# Cross-tenant é 404, nunca 403: um 403 confirmaria que o id existe em algum lugar.
code="$(status_of GET "$API/api/v1/vulnerabilities/$VULN_ID/attachments" "$TOKEN_B")"
[ "$code" = "404" ] || fail "empresa B listou anexos da empresa A (HTTP $code, esperado 404)"
code="$(status_of GET "$API/api/v1/vulnerabilities/$VULN_ID/attachments/$ATTACHMENT_ID/download" "$TOKEN_B")"
[ "$code" = "404" ] || fail "empresa B baixou anexo da empresa A (HTTP $code, esperado 404)"
pass "anexos isolados entre empresas, com 404"

code="$(status_of DELETE "$API/api/v1/vulnerabilities/$VULN_ID/attachments/$ATTACHMENT_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "remoção do anexo devolveu $code, esperado 204"
pass "anexo removido"

step "15. Relatório executivo"
report_code="$(curl -s -o "$REPORT" -w '%{http_code}' -H "Authorization: Bearer $TOKEN_A" \
  "$API/api/v1/reports/executive")"
[ "$report_code" = "200" ] || fail "relatório executivo devolveu $report_code"
[ "$(head -c 5 "$REPORT")" = "%PDF-" ] || fail "o relatório não começa com %PDF-"
[ "$(wc -c < "$REPORT")" -gt 1000 ] || fail "o relatório tem tamanho implausível"
pass "relatório executivo é um PDF com conteúdo"

printf '\n\033[32mSmoke test concluído com sucesso.\033[0m\n'
