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

# scan_upload PROJECT_ID FORMAT ARQUIVO TOKEN -> corpo da resposta, falha em não-2xx
#
# Multipart, e não JSON: o relatório é um arquivo. `projectId` e `format` viajam como campos
# do mesmo formulário porque é assim que o cliente os manda — o controller os recebe como
# parâmetros de requisição, que é o que o contêiner faz com um campo não-arquivo.
scan_upload() {
  local project="$1" format="$2" file="$3" token="$4"
  local raw code payload
  raw="$(curl -s -w '\n%{http_code}' -X POST "$API/api/v1/scan-imports" \
    -H "Authorization: Bearer $token" \
    -F "projectId=$project" -F "format=$format" -F "file=@$file")"
  code="$(printf '%s' "$raw" | tail -n1)"
  payload="$(printf '%s' "$raw" | sed '$d')"
  if [ "${code:0:1}" != "2" ]; then
    echo "  envio de $file ($format) devolveu $code" >&2
    echo "  corpo: $payload" >&2
    return 1
  fi
  printf '%s' "$payload"
}

# scan_upload_status ... -> só o código, para os casos em que a recusa é o esperado
scan_upload_status() {
  local project="$1" format="$2" file="$3" token="$4"
  curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/scan-imports" \
    -H "Authorization: Bearer $token" \
    -F "projectId=$project" -F "format=$format" -F "file=@$file"
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

step "16. Importação de relatórios de varredura"

# Um relatório de varredura chega como arquivo e vira **proposta**, não vulnerabilidade: o
# envio só encena os achados em uma área de staging, e quem cria alguma coisa é a confirmação.
# Cada formato é enviado duas vezes de propósito. A segunda vez é o que prova, contra o banco
# de verdade, que a impressão digital de um achado já importado o marca como duplicado em vez
# de criar uma segunda cópia de algo que alguém já está tratando.

SCAN_DIR="$(mktemp -d -t smoke-scan-XXXXXX)"
# Substitui o trap da seção 14 e repete o que ele fazia: um trap novo não acumula, ele troca.
trap 'rm -f "$EVIDENCE" "$DOWNLOADED" "$REPORT"; rm -rf "$SCAN_DIR"' EXIT

# O alvo de um achado é casado com o `identifier` de um ativo **do projeto escolhido**, então
# os relatórios abaixo apontam para os identificadores criados na seção 4. ZAP e nuclei
# reportam URL, e é a URL inteira que precisa bater com o identificador — daí este ativo.
WEB_IDENTIFIER="https://web-$SUFFIX.smoke.test"
web_asset="$(call POST "$API/api/v1/assets" "$TOKEN_A" "{
  \"projectId\": $PROJECT_ID,
  \"name\": \"Portal web\",
  \"type\": \"WEBSITE\",
  \"identifier\": \"$WEB_IDENTIFIER\",
  \"environment\": \"PRODUCTION\",
  \"criticality\": \"HIGH\"
}")" || fail "criação do ativo web falhou"
WEB_ASSET_ID="$(printf '%s' "$web_asset" | jq -r '.id')"
pass "ativo web $WEB_ASSET_ID criado para os alvos em forma de URL"

backlog_before="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listagem falhou"
BACKLOG_BEFORE="$(printf '%s' "$backlog_before" | jq -r '.totalElements')"

# --- nmap: casa um alvo, deixa outro sem ativo, e ignora porta aberta ---------
cat > "$SCAN_DIR/nmap.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://svn.nmap.org/nmap/docs/nmap.dtd">
<nmaprun scanner="nmap" args="nmap -sV --script vuln -oX -" start="1700000000" version="7.94">
  <host>
    <status state="up" reason="echo-reply"/>
    <address addr="10.44.0.10" addrtype="ipv4"/>
    <hostnames><hostname name="api-$SUFFIX.smoke.test" type="PTR"/></hostnames>
    <ports>
      <port protocol="tcp" portid="80">
        <state state="open" reason="syn-ack"/>
        <service name="http" product="nginx" version="1.18.0"/>
      </port>
      <port protocol="tcp" portid="443">
        <state state="open" reason="syn-ack"/>
        <script id="ssl-heartbleed" output="VULNERABLE: The Heartbleed Bug is a serious vulnerability in OpenSSL. References: CVE-2014-0160"/>
      </port>
    </ports>
    <hostscript>
      <script id="smb-vuln-ms17-010" output="VULNERABLE: Remote Code Execution in Microsoft SMBv1. IDs: CVE:CVE-2017-0143"/>
    </hostscript>
  </host>
  <host>
    <status state="up" reason="reset"/>
    <address addr="198.51.100.23" addrtype="ipv4"/>
    <hostscript>
      <script id="ssl-poodle" output="VULNERABLE: SSL POODLE information leak"/>
    </hostscript>
  </host>
</nmaprun>
XML

nmap_import="$(scan_upload "$PROJECT_ID" NMAP_XML "$SCAN_DIR/nmap.xml" "$TOKEN_A")" \
  || fail "envio do relatório nmap falhou"
NMAP_ID="$(printf '%s' "$nmap_import" | jq -r '.id')"
[ "$(printf '%s' "$nmap_import" | jq -r '.status')" = "PENDING" ] || fail "a importação não nasceu PENDING"
# Três resultados de script e duas portas abertas no arquivo: só os scripts viram achado.
# Uma porta aberta não é uma vulnerabilidade, e importá-la encheria o backlog de ruído.
[ "$(printf '%s' "$nmap_import" | jq -r '.totalFindings')" = "3" ] \
  || fail "o nmap deveria render 3 achados (só resultados de script NSE)"
[ "$(printf '%s' "$nmap_import" | jq -r '.matchedCount')" = "2" ] || fail "contador de achados com ativo incorreto"
[ "$(printf '%s' "$nmap_import" | jq -r '.unmatchedCount')" = "1" ] || fail "contador de achados sem ativo incorreto"
[ "$(printf '%s' "$nmap_import" | jq -r '.duplicateCount')" = "0" ] || fail "importação inédita trouxe duplicados"
pass "importação $NMAP_ID criada pendente: 3 achados, 2 com ativo, 1 sem ativo"

# O envio não cria nada: é só uma proposta até alguém confirmar.
backlog_staged="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listagem falhou"
[ "$(printf '%s' "$backlog_staged" | jq -r '.totalElements')" = "$BACKLOG_BEFORE" ] \
  || fail "o envio do relatório criou vulnerabilidade antes da confirmação"
pass "nada foi criado pelo envio"

# O alvo que não existe no inventário fica esperando uma pessoa: o importador nunca cria ativo.
FINDING_ID="$(printf '%s' "$nmap_import" | jq -r '[.findings[] | select(.status == "UNMATCHED")][0].id')"
mapped="$(call PATCH "$API/api/v1/scan-imports/$NMAP_ID/findings/$FINDING_ID" "$TOKEN_A" \
  "{\"assetId\": $ASSET_ID}")" || fail "mapeamento do achado falhou"
[ "$(printf '%s' "$mapped" | jq -r '.status')" = "MATCHED" ] || fail "o achado mapeado não ficou MATCHED"
[ "$(printf '%s' "$mapped" | jq -r '.assetId')" = "$ASSET_ID" ] || fail "o ativo do achado não foi gravado"
preview="$(call GET "$API/api/v1/scan-imports/$NMAP_ID" "$TOKEN_A")" || fail "prévia falhou"
[ "$(printf '%s' "$preview" | jq -r '.matchedCount')" = "3" ] || fail "o mapeamento não recontou a importação"
[ "$(printf '%s' "$preview" | jq -r '.unmatchedCount')" = "0" ] || fail "ainda há achado sem ativo"
pass "achado $FINDING_ID mapeado à mão e contadores recalculados"

# A trilha recebe uma linha por importação, e não uma por vulnerabilidade criada: é o que
# mantém a auditoria legível depois de um relatório de quatrocentos achados.
creates_before="$(call GET "$API/api/v1/audit-logs?size=1&entityType=Vulnerability&action=CREATE" "$TOKEN_A")" \
  || fail "consulta de auditoria falhou"
CREATES_BEFORE="$(printf '%s' "$creates_before" | jq -r '.totalElements')"

confirmed="$(call POST "$API/api/v1/scan-imports/$NMAP_ID/confirm" "$TOKEN_A")" || fail "confirmação falhou"
[ "$(printf '%s' "$confirmed" | jq -r '.status')" = "CONFIRMED" ] || fail "a importação não ficou CONFIRMED"
[ "$(printf '%s' "$confirmed" | jq -r '.importedCount')" = "3" ] || fail "não foram criadas 3 vulnerabilidades"
[ "$(printf '%s' "$confirmed" | jq -r '.skippedCount')" = "0" ] || fail "algum achado foi ignorado sem motivo"
[ "$(printf '%s' "$confirmed" | jq '[.findings[] | select(.status == "IMPORTED" and (.vulnerabilityId | type) == "number")] | length')" = "3" ] \
  || fail "algum achado importado não aponta para a vulnerabilidade criada"
backlog_after="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listagem falhou"
[ "$(printf '%s' "$backlog_after" | jq -r '.totalElements')" = "$((BACKLOG_BEFORE + 3))" ] \
  || fail "o backlog não cresceu exatamente 3 vulnerabilidades"
pass "confirmação criou 3 vulnerabilidades, uma por achado com ativo"

creates_after="$(call GET "$API/api/v1/audit-logs?size=1&entityType=Vulnerability&action=CREATE" "$TOKEN_A")" \
  || fail "consulta de auditoria falhou"
[ "$(printf '%s' "$creates_after" | jq -r '.totalElements')" = "$CREATES_BEFORE" ] \
  || fail "a confirmação escreveu CREATE por vulnerabilidade e enterrou a trilha"
scan_audit="$(call GET "$API/api/v1/audit-logs?size=20&entityType=ScanImport&action=SCAN_IMPORT" "$TOKEN_A")" \
  || fail "consulta de auditoria falhou"
printf '%s' "$scan_audit" | jq -e ".content[] | select(.entityId == $NMAP_ID)" >/dev/null \
  || fail "a confirmação não deixou linha SCAN_IMPORT na auditoria"
[ "$(printf '%s' "$scan_audit" | jq "[.content[] | select(.entityId == $NMAP_ID)] | length")" = "1" ] \
  || fail "a confirmação deixou mais de uma linha de auditoria"
pass "auditoria tem exatamente uma linha SCAN_IMPORT e nenhum CREATE por achado"

# Uma importação encerrada não volta atrás: os dois caminhos respondem 409 e não 400, porque
# o pedido está bem formado — o que está errado é o estado da linha.
code="$(status_of POST "$API/api/v1/scan-imports/$NMAP_ID/confirm" "$TOKEN_A")"
[ "$code" = "409" ] || fail "segunda confirmação devolveu $code, esperado 409"
code="$(status_of DELETE "$API/api/v1/scan-imports/$NMAP_ID" "$TOKEN_A")"
[ "$code" = "409" ] || fail "descarte de importação confirmada devolveu $code, esperado 409"
pass "só uma importação pendente pode ser confirmada ou descartada"

# O mesmo arquivo de novo: nenhum achado novo, todos marcados como já registrados. O que isso
# protege é o trabalho humano — a vulnerabilidade da primeira importação pode já ter status,
# responsável e discussão, e reimportar não pode desfazer nada disso.
nmap_again="$(scan_upload "$PROJECT_ID" NMAP_XML "$SCAN_DIR/nmap.xml" "$TOKEN_A")" \
  || fail "segundo envio do relatório nmap falhou"
NMAP_AGAIN_ID="$(printf '%s' "$nmap_again" | jq -r '.id')"
[ "$(printf '%s' "$nmap_again" | jq -r '.totalFindings')" = "3" ] || fail "o relatório mudou de tamanho"
[ "$(printf '%s' "$nmap_again" | jq -r '.duplicateCount')" = "3" ] \
  || fail "a reimportação não reconheceu todos os achados como duplicados"
[ "$(printf '%s' "$nmap_again" | jq -r '.matchedCount')" = "0" ] || fail "duplicado deveria vencer sobre com ativo"
[ "$(printf '%s' "$nmap_again" | jq -r '.unmatchedCount')" = "0" ] || fail "contador de sem ativo incorreto"
confirmed_again="$(call POST "$API/api/v1/scan-imports/$NMAP_AGAIN_ID/confirm" "$TOKEN_A")" \
  || fail "confirmação da reimportação falhou"
[ "$(printf '%s' "$confirmed_again" | jq -r '.importedCount')" = "0" ] || fail "a reimportação criou vulnerabilidade"
[ "$(printf '%s' "$confirmed_again" | jq -r '.skippedCount')" = "3" ] || fail "os duplicados não foram ignorados"
backlog_dup="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listagem falhou"
[ "$(printf '%s' "$backlog_dup" | jq -r '.totalElements')" = "$((BACKLOG_BEFORE + 3))" ] \
  || fail "reimportar o mesmo relatório mudou o tamanho do backlog"
pass "reimportação do mesmo arquivo: 3 duplicados, 0 criados"

# --- ZAP: uma instância por alerta, CVSS arredondado e o primeiro CVE da lista -
cat > "$SCAN_DIR/zap.json" <<JSON
{
  "@programName": "ZAP",
  "@version": "2.14.0",
  "site": [
    {
      "@name": "$WEB_IDENTIFIER",
      "@host": "web-$SUFFIX.smoke.test",
      "@port": "443",
      "alerts": [
        {
          "pluginid": "40012",
          "name": "Cross Site Scripting (Reflected)",
          "riskcode": "3",
          "desc": "<p>O termo pesquisado volta sem escape.</p>",
          "instances": [{"uri": "$WEB_IDENTIFIER", "method": "GET", "param": "q"}]
        },
        {
          "pluginid": "10038",
          "name": "Remote Code Execution - Log4Shell",
          "riskcode": "3",
          "desc": "<p>Versao vulneravel do Log4j.</p>",
          "cveid": "CVE-2021-44228, CVE-2021-45046",
          "cvssScore": "7.53",
          "instances": [{"uri": "$WEB_IDENTIFIER", "method": "GET"}]
        }
      ]
    }
  ]
}
JSON

zap_import="$(scan_upload "$PROJECT_ID" ZAP_JSON "$SCAN_DIR/zap.json" "$TOKEN_A")" \
  || fail "envio do relatório ZAP falhou"
ZAP_ID="$(printf '%s' "$zap_import" | jq -r '.id')"
[ "$(printf '%s' "$zap_import" | jq -r '.totalFindings')" = "2" ] || fail "o ZAP deveria render 2 achados"
[ "$(printf '%s' "$zap_import" | jq -r '.matchedCount')" = "2" ] || fail "os dois alertas deveriam achar o ativo web"
pass "importação $ZAP_ID criada: 2 achados, os dois com ativo"

zap_confirmed="$(call POST "$API/api/v1/scan-imports/$ZAP_ID/confirm" "$TOKEN_A")" \
  || fail "confirmação do ZAP falhou"
[ "$(printf '%s' "$zap_confirmed" | jq -r '.importedCount')" = "2" ] || fail "o ZAP não criou 2 vulnerabilidades"
LOG4SHELL_ID="$(printf '%s' "$zap_confirmed" | jq -r '[.findings[] | select(.ruleId == "10038")][0].vulnerabilityId')"
log4shell="$(call GET "$API/api/v1/vulnerabilities/$LOG4SHELL_ID" "$TOKEN_A")" || fail "leitura da vulnerabilidade falhou"
# 7.53 não cabe em NUMERIC(3,1): o normalizador arredonda para 7.5 em vez de deixar o banco
# recusar a linha inteira na confirmação.
[ "$(printf '%s' "$log4shell" | jq -r '.cvssScore')" = "7.5" ] || fail "o CVSS do ZAP não foi normalizado para 7.5"
# Um CVE por achado, e é o primeiro bem formado da lista que o ZAP mandou.
[ "$(printf '%s' "$log4shell" | jq -r '.cve')" = "CVE-2021-44228" ] || fail "o CVE do ZAP não foi extraído"
[ "$(printf '%s' "$log4shell" | jq -r '.severity')" = "HIGH" ] || fail "riskcode 3 deveria virar HIGH"
pass "vulnerabilidade $LOG4SHELL_ID criada com CVSS 7.5, CVE-2021-44228 e severidade HIGH"

zap_again="$(scan_upload "$PROJECT_ID" ZAP_JSON "$SCAN_DIR/zap.json" "$TOKEN_A")" \
  || fail "segundo envio do relatório ZAP falhou"
ZAP_AGAIN_ID="$(printf '%s' "$zap_again" | jq -r '.id')"
[ "$(printf '%s' "$zap_again" | jq -r '.duplicateCount')" = "2" ] \
  || fail "a reimportação do ZAP não marcou todos os achados como duplicados"
pass "reimportação do ZAP: 2 duplicados, 0 com ativo"

# O descarte joga a proposta fora; as linhas de achado ficam, e o arquivo em disco some.
code="$(status_of DELETE "$API/api/v1/scan-imports/$ZAP_AGAIN_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "descarte devolveu $code, esperado 204"
discarded="$(call GET "$API/api/v1/scan-imports/$ZAP_AGAIN_ID" "$TOKEN_A")" || fail "prévia da descartada falhou"
[ "$(printf '%s' "$discarded" | jq -r '.status')" = "DISCARDED" ] || fail "a importação não ficou DISCARDED"
[ "$(printf '%s' "$discarded" | jq '.findings | length')" = "2" ] \
  || fail "o descarte apagou o que o relatório encontrou"
pass "importação $ZAP_AGAIN_ID descartada, mantendo o que o relatório encontrou"

# --- nuclei: um JSON por linha, e a linha que não é JSON é pulada -------------
cat > "$SCAN_DIR/nuclei.jsonl" <<JSONL
{"template-id":"springboot-actuators","info":{"name":"Spring Boot Actuator Exposure","description":"Actuator exposto sem autenticacao.","severity":"high","classification":{"cvss-score":8.6,"cve-id":["CVE-2023-1234"]}},"host":"$WEB_IDENTIFIER","matched-at":"$WEB_IDENTIFIER","timestamp":"2023-11-13T10:17:00Z"}
nuclei: connection reset by peer while writing this line
{"template-id":"tech-detect","info":{"name":"Wappalyzer Technology Detection","description":"Identifica tecnologias expostas.","severity":"info"},"host":"$WEB_IDENTIFIER","matched-at":"$WEB_IDENTIFIER","timestamp":"2023-11-13T10:16:30Z"}
JSONL

nuclei_import="$(scan_upload "$PROJECT_ID" NUCLEI_JSONL "$SCAN_DIR/nuclei.jsonl" "$TOKEN_A")" \
  || fail "envio do relatório nuclei falhou"
NUCLEI_ID="$(printf '%s' "$nuclei_import" | jq -r '.id')"
# Três linhas no arquivo, duas viram achado: uma linha corrompida no meio do fluxo não pode
# derrubar o relatório inteiro.
[ "$(printf '%s' "$nuclei_import" | jq -r '.totalFindings')" = "2" ] \
  || fail "o nuclei deveria render 2 achados e pular a linha que não é JSON"
[ "$(printf '%s' "$nuclei_import" | jq -r '.matchedCount')" = "2" ] || fail "os dois achados deveriam achar o ativo web"
pass "importação $NUCLEI_ID criada: 2 achados, linha corrompida ignorada"

nuclei_confirmed="$(call POST "$API/api/v1/scan-imports/$NUCLEI_ID/confirm" "$TOKEN_A")" \
  || fail "confirmação do nuclei falhou"
[ "$(printf '%s' "$nuclei_confirmed" | jq -r '.importedCount')" = "2" ] || fail "o nuclei não criou 2 vulnerabilidades"
pass "confirmação do nuclei criou 2 vulnerabilidades"

nuclei_again="$(scan_upload "$PROJECT_ID" NUCLEI_JSONL "$SCAN_DIR/nuclei.jsonl" "$TOKEN_A")" \
  || fail "segundo envio do relatório nuclei falhou"
NUCLEI_AGAIN_ID="$(printf '%s' "$nuclei_again" | jq -r '.id')"
[ "$(printf '%s' "$nuclei_again" | jq -r '.duplicateCount')" = "2" ] \
  || fail "a reimportação do nuclei não marcou todos os achados como duplicados"
code="$(status_of DELETE "$API/api/v1/scan-imports/$NUCLEI_AGAIN_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "descarte da reimportação do nuclei devolveu $code, esperado 204"
pass "reimportação do nuclei: 2 duplicados, descartada em seguida"

# --- o teto por arquivo -------------------------------------------------------
# A importação é síncrona, e é esse teto que a mantém assim: acima dele o envio é recusado
# antes de qualquer gravação, em vez de a requisição virar um trabalho de minutos.
awk -v host="$WEB_IDENTIFIER" 'BEGIN {
  for (i = 1; i <= 2001; i++)
    printf "{\"template-id\":\"limite-%d\",\"info\":{\"name\":\"Achado %d\",\"severity\":\"low\"},\"matched-at\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n", i, i, host
}' > "$SCAN_DIR/nuclei-grande.jsonl"

history_before="$(call GET "$API/api/v1/scan-imports?size=1" "$TOKEN_A")" || fail "histórico falhou"
HISTORY_BEFORE="$(printf '%s' "$history_before" | jq -r '.totalElements')"
code="$(scan_upload_status "$PROJECT_ID" NUCLEI_JSONL "$SCAN_DIR/nuclei-grande.jsonl" "$TOKEN_A")"
[ "$code" = "400" ] || fail "relatório acima do teto devolveu $code, esperado 400"
history_after="$(call GET "$API/api/v1/scan-imports?size=1" "$TOKEN_A")" || fail "histórico falhou"
[ "$(printf '%s' "$history_after" | jq -r '.totalElements')" = "$HISTORY_BEFORE" ] \
  || fail "o relatório recusado deixou uma importação no histórico"
pass "relatório acima do teto é recusado sem deixar linha nenhuma"

# --- histórico ----------------------------------------------------------------
history="$(call GET "$API/api/v1/scan-imports?size=50" "$TOKEN_A")" || fail "histórico falhou"
printf '%s' "$history" | jq -e ".content[] | select(.id == $NMAP_ID and .status == \"CONFIRMED\")" >/dev/null \
  || fail "a importação confirmada não aparece no histórico"
printf '%s' "$history" | jq -e ".content[] | select(.id == $ZAP_AGAIN_ID and .status == \"DISCARDED\")" >/dev/null \
  || fail "a importação descartada não aparece no histórico"
# A listagem carrega os contadores e não os achados: uma página de vinte importações com todos
# os achados de cada uma seriam milhares de linhas para desenhar seis números.
printf '%s' "$history" | jq -e '.content[0] | has("findings")' >/dev/null \
  && fail "o histórico está carregando os achados de cada importação"
pass "histórico lista as importações com os contadores, sem os achados"

# --- isolamento entre empresas ------------------------------------------------
# 404 e nunca 403, como no resto da API: um 403 confirmaria que o id existe.
for path in "/scan-imports/$NMAP_ID" "/scan-imports/$ZAP_ID"; do
  code="$(status_of GET "$API/api/v1$path" "$TOKEN_B")"
  [ "$code" = "404" ] || fail "empresa B leu $path da empresa A (HTTP $code, esperado 404)"
done
code="$(status_of DELETE "$API/api/v1/scan-imports/$NMAP_ID" "$TOKEN_B")"
[ "$code" = "404" ] || fail "empresa B descartou importação da empresa A (HTTP $code, esperado 404)"
code="$(scan_upload_status "$PROJECT_ID" NMAP_XML "$SCAN_DIR/nmap.xml" "$TOKEN_B")"
[ "$code" = "404" ] || fail "empresa B importou para um projeto da empresa A (HTTP $code, esperado 404)"
history_b="$(call GET "$API/api/v1/scan-imports?size=50" "$TOKEN_B")" || fail "histórico da empresa B falhou"
printf '%s' "$history_b" | jq -e ".content[] | select(.id == $NMAP_ID)" >/dev/null \
  && fail "o histórico da empresa B expôs uma importação da empresa A"
pass "importações isoladas entre empresas, com 404"

printf '\n\033[32mSmoke test concluído com sucesso.\033[0m\n'
