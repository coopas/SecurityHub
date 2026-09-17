#!/usr/bin/env bash
# End-to-end smoke test against a running stack. Exercises the real API with real data:
# nothing here is mocked or seeded behind the scenes.
#
#   ./scripts/smoke-test.sh [api-base-url] [frontend-url] [management-url]
#
# Defaults match docker-compose.yml. The management port is separate from the application port
# — the actuator is no longer served on $API — and comes as the third argument so that the
# script also runs against a stack that is not the compose one.

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
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1"; exit 1; }
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
    echo "  request $method $url returned $code" >&2
    echo "  body: $payload" >&2
    return 1
  fi
  printf '%s' "$payload"
}

# scan_upload PROJECT_ID FORMAT FILE TOKEN -> response body, fails on non-2xx
#
# Multipart, not JSON: the report is a file. `projectId` and `format` travel as fields of the
# same form because that is how the client sends them — the controller receives them as request
# parameters, which is what the container does with a non-file field.
scan_upload() {
  local project="$1" format="$2" file="$3" token="$4"
  local raw code payload
  raw="$(curl -s -w '\n%{http_code}' -X POST "$API/api/v1/scan-imports" \
    -H "Authorization: Bearer $token" \
    -F "projectId=$project" -F "format=$format" -F "file=@$file")"
  code="$(printf '%s' "$raw" | tail -n1)"
  payload="$(printf '%s' "$raw" | sed '$d')"
  if [ "${code:0:1}" != "2" ]; then
    echo "  upload of $file ($format) returned $code" >&2
    echo "  body: $payload" >&2
    return 1
  fi
  printf '%s' "$payload"
}

# scan_upload_status ... -> the status code only, for the cases where refusal is expected
scan_upload_status() {
  local project="$1" format="$2" file="$3" token="$4"
  curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/scan-imports" \
    -H "Authorization: Bearer $token" \
    -F "projectId=$project" -F "format=$format" -F "file=@$file"
}

step "1. Service health"
health="$(curl -s "$MGMT/actuator/health" | jq -r '.status' 2>/dev/null || echo DOWN)"
[ "$health" = "UP" ] || fail "backend is not UP (received: $health, via $MGMT)"
pass "backend responding, health at $MGMT"

# Health has to stay public: the child context of the management port inherits the
# application's security chain, and without the /actuator/health matcher the container
# healthcheck would answer 401 and the stack would never come up.
code="$(status_of GET "$MGMT/actuator/health")"
[ "$code" = "200" ] || fail "health on the management port returned $code with no token (expected 200)"
pass "the health probe does not depend on authentication"

web_status="$(curl -s -o /dev/null -w '%{http_code}' "$WEB/")"
[ "$web_status" = "200" ] || fail "frontend returned $web_status at $WEB"
pass "frontend serving at $WEB"

step "2. Protected endpoint with no token"
code="$(status_of GET "$API/api/v1/projects")"
[ "$code" = "401" ] || fail "expected 401 with no token, received $code"
pass "GET /projects with no token returns 401"

code="$(status_of GET "$API/api/v1/projects" "token-invalido-qualquer")"
[ "$code" = "401" ] || fail "expected 401 with an invalid token, received $code"
pass "an invalid token returns 401"

step "3. Company A registration and authentication"
reg_a="$(call POST "$API/api/v1/auth/register" "" "{
  \"companyName\": \"Smoke A $SUFFIX\",
  \"name\": \"Admin A\",
  \"email\": \"admin-a-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "company A registration failed"
pass "company A created"

login_a="$(call POST "$API/api/v1/auth/login" "" "{
  \"email\": \"admin-a-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "company A login failed"
TOKEN_A="$(printf '%s' "$login_a" | jq -r '.accessToken')"
USER_A="$(printf '%s' "$login_a" | jq -r '.user.id')"
[ -n "$TOKEN_A" ] && [ "$TOKEN_A" != "null" ] || fail "login did not return an accessToken"
[ "$(printf '%s' "$login_a" | jq -r '.user.role')" = "ADMIN" ] || fail "the first user is not ADMIN"
pass "login returned an access token and the ADMIN role"

me="$(call GET "$API/api/v1/auth/me" "$TOKEN_A")" || fail "/auth/me failed"
[ "$(printf '%s' "$me" | jq -r '.id')" = "$USER_A" ] || fail "/auth/me returned a different user"
pass "/auth/me consistent with the token"

# The leak the change closed. /actuator/metrics was not in PUBLIC_ENDPOINTS, fell into
# anyRequest().authenticated() and was therefore readable by any authenticated user of the
# application — including a VIEWER. Now there is no handler at all on this port: with a valid
# token the answer is 404, and that is why the check uses a token.
for path in /actuator/metrics /actuator/prometheus /actuator/health /actuator/info; do
  code="$(status_of GET "$API$path" "$TOKEN_A")"
  [ "$code" = "404" ] || fail "$path answers $code on the application port for an authenticated user (expected 404)"
done
pass "no actuator endpoint is served on the application port, not even with a token"

step "4. Domain flow in company A"
project="$(call POST "$API/api/v1/projects" "$TOKEN_A" "{
  \"name\": \"Projeto Smoke $SUFFIX\",
  \"description\": \"Criado pelo smoke test\"
}")" || fail "project creation failed"
PROJECT_ID="$(printf '%s' "$project" | jq -r '.id')"
pass "project $PROJECT_ID created"

asset="$(call POST "$API/api/v1/assets" "$TOKEN_A" "{
  \"projectId\": $PROJECT_ID,
  \"name\": \"API de pagamentos\",
  \"type\": \"API\",
  \"identifier\": \"api-$SUFFIX.smoke.test\",
  \"environment\": \"PRODUCTION\",
  \"criticality\": \"HIGH\"
}")" || fail "asset creation failed"
ASSET_ID="$(printf '%s' "$asset" | jq -r '.id')"
pass "asset $ASSET_ID created"

vuln="$(call POST "$API/api/v1/vulnerabilities" "$TOKEN_A" "{
  \"assetId\": $ASSET_ID,
  \"title\": \"SQL injection no endpoint de busca\",
  \"description\": \"Parâmetro concatenado diretamente na query\",
  \"severity\": \"CRITICAL\",
  \"cvssScore\": 9.1,
  \"cve\": \"CVE-2024-12345\",
  \"discoveredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
}")" || fail "vulnerability creation failed"
VULN_ID="$(printf '%s' "$vuln" | jq -r '.id')"
[ "$(printf '%s' "$vuln" | jq -r '.status')" = "OPEN" ] || fail "the vulnerability was not born OPEN"
pass "vulnerability $VULN_ID created with status OPEN"

assigned="$(call PATCH "$API/api/v1/vulnerabilities/$VULN_ID/assignee" "$TOKEN_A" \
  "{\"userId\": $USER_A}")" || fail "assignment failed"
[ "$(printf '%s' "$assigned" | jq -r '.assignedTo.id')" = "$USER_A" ] || fail "the assignee was not stored"
pass "vulnerability assigned"

progress="$(call PATCH "$API/api/v1/vulnerabilities/$VULN_ID/status" "$TOKEN_A" \
  "{\"status\": \"IN_PROGRESS\"}")" || fail "change to IN_PROGRESS failed"
[ "$(printf '%s' "$progress" | jq -r '.status')" = "IN_PROGRESS" ] || fail "the status did not change"
pass "status changed to IN_PROGRESS"

comment="$(call POST "$API/api/v1/vulnerabilities/$VULN_ID/comments" "$TOKEN_A" \
  "{\"content\": \"Correção iniciada pelo smoke test\"}")" || fail "comment failed"
[ "$(printf '%s' "$comment" | jq -r '.content')" != "null" ] || fail "empty comment"
pass "comment recorded"

resolved="$(call PATCH "$API/api/v1/vulnerabilities/$VULN_ID/status" "$TOKEN_A" \
  "{\"status\": \"RESOLVED\"}")" || fail "change to RESOLVED failed"
[ "$(printf '%s' "$resolved" | jq -r '.status')" = "RESOLVED" ] || fail "incorrect final status"
[ "$(printf '%s' "$resolved" | jq -r '.resolvedAt')" != "null" ] || fail "resolvedAt was not filled in"
pass "status RESOLVED filled in resolvedAt"

step "5. Audit trail"
audit="$(call GET "$API/api/v1/audit-logs?size=50" "$TOKEN_A")" || fail "audit query failed"
audit_count="$(printf '%s' "$audit" | jq '.content | length')"
[ "$audit_count" -gt 0 ] || fail "empty audit trail"
printf '%s' "$audit" | jq -e '.content[] | select(.action == "STATUS_CHANGE")' >/dev/null \
  || fail "the status change was not audited"
printf '%s' "$audit" | grep -qiE '"(password|passwordHash|senha|token|secret)"[[:space:]]*:[[:space:]]*"[^*]' \
  && fail "the audit trail contains an unmasked sensitive field"
pass "the audit trail recorded $audit_count events, with no sensitive fields"

step "6. Dashboard"
summary="$(call GET "$API/api/v1/dashboard/summary" "$TOKEN_A")" || fail "dashboard/summary failed"
[ "$(printf '%s' "$summary" | jq -r '.totalVulnerabilities')" -ge 1 ] || fail "summary did not count the vulnerability"
[ "$(printf '%s' "$summary" | jq -r '.totalProjects')" -ge 1 ] || fail "summary did not count the project"
[ "$(printf '%s' "$summary" | jq -r '.totalAssets')" -ge 1 ] || fail "summary did not count the asset"
pass "the dashboard reflects the data created"

call GET "$API/api/v1/dashboard/severity-distribution" "$TOKEN_A" >/dev/null || fail "severity-distribution failed"
call GET "$API/api/v1/dashboard/status-distribution" "$TOKEN_A" >/dev/null || fail "status-distribution failed"
call GET "$API/api/v1/dashboard/trend?days=30" "$TOKEN_A" >/dev/null || fail "trend failed"
pass "the remaining dashboard endpoints respond"

step "7. Isolation between companies"
call POST "$API/api/v1/auth/register" "" "{
  \"companyName\": \"Smoke B $SUFFIX\",
  \"name\": \"Admin B\",
  \"email\": \"admin-b-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}" >/dev/null || fail "company B registration failed"

login_b="$(call POST "$API/api/v1/auth/login" "" "{
  \"email\": \"admin-b-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "company B login failed"
TOKEN_B="$(printf '%s' "$login_b" | jq -r '.accessToken')"
pass "company B created and authenticated"

for pair in "projects:$PROJECT_ID" "assets:$ASSET_ID" "vulnerabilities:$VULN_ID"; do
  resource="${pair%%:*}"; id="${pair##*:}"
  code="$(status_of GET "$API/api/v1/$resource/$id" "$TOKEN_B")"
  [ "$code" = "404" ] || fail "company B read company A's /$resource/$id (HTTP $code, expected 404)"
  code="$(status_of DELETE "$API/api/v1/$resource/$id" "$TOKEN_B")"
  [ "$code" = "404" ] || fail "company B deleted company A's /$resource/$id (HTTP $code, expected 404)"
done
pass "company B receives 404 on every resource of company A"

list_b="$(call GET "$API/api/v1/projects?size=100" "$TOKEN_B")" || fail "company B listing failed"
printf '%s' "$list_b" | jq -e ".content[] | select(.id == $PROJECT_ID)" >/dev/null \
  && fail "company B's listing exposed company A's project"
pass "company B's listings contain no data from company A"

audit_b="$(call GET "$API/api/v1/audit-logs?size=100" "$TOKEN_B")" || fail "company B audit query failed"
printf '%s' "$audit_b" | jq -e ".content[] | select(.actorId == $USER_A)" >/dev/null \
  && fail "company B's audit trail exposed company A's events"
pass "audit trail isolated between companies"

step "8. The resource still exists for company A"
call GET "$API/api/v1/vulnerabilities/$VULN_ID" "$TOKEN_A" >/dev/null \
  || fail "company A lost access to its own resource"
pass "company A still sees its own data"

step "9. Session renewal and sign-out"
REFRESH_A="$(printf '%s' "$login_a" | jq -r '.refreshToken')"
[ -n "$REFRESH_A" ] && [ "$REFRESH_A" != "null" ] || fail "login did not return a refreshToken"

rotated="$(call POST "$API/api/v1/auth/refresh" "" "{\"refreshToken\": \"$REFRESH_A\"}")" \
  || fail "refresh failed"
ROTATED_TOKEN="$(printf '%s' "$rotated" | jq -r '.accessToken')"
ROTATED_REFRESH="$(printf '%s' "$rotated" | jq -r '.refreshToken')"
[ "$ROTATED_REFRESH" != "$REFRESH_A" ] || fail "refresh returned the same token (no rotation happened)"
call GET "$API/api/v1/auth/me" "$ROTATED_TOKEN" >/dev/null || fail "the renewed token is not accepted"
pass "refresh rotates the token and the new access token works"

# Presenting the just-rotated token again inside the grace window (REUSE_GRACE, 30s) returns
# 200 on purpose: two tabs, a network retry or a timeout make the same token arrive twice within
# seconds, and without the window reuse detection would log the legitimate user out on every
# benign race. Outside the window the whole family dies — that is covered by
# RefreshTokenIntegrationTest, which controls the clock; what is checked here is that the benign
# path does not log anyone out.
code="$(status_of POST "$API/api/v1/auth/refresh" "" "{\"refreshToken\": \"$REFRESH_A\"}")"
[ "$code" = "200" ] || fail "reuse inside the grace window returned $code, expected 200"
pass "immediate reuse falls into the grace window and does not log the user out"

# Each login opens a new session, so this one does not affect the TOKEN_A used above.
login_logout="$(call POST "$API/api/v1/auth/login" "" "{
  \"email\": \"admin-a-$SUFFIX@smoke.test\",
  \"password\": \"$PASSWORD\"
}")" || fail "login for the logout test failed"
LOGOUT_REFRESH="$(printf '%s' "$login_logout" | jq -r '.refreshToken')"
code="$(status_of POST "$API/api/v1/auth/logout" "" "{\"refreshToken\": \"$LOGOUT_REFRESH\"}")"
[ "$code" = "204" ] || fail "logout returned $code, expected 204"
code="$(status_of POST "$API/api/v1/auth/refresh" "" "{\"refreshToken\": \"$LOGOUT_REFRESH\"}")"
[ "$code" = "401" ] || fail "refresh after logout returned $code, expected 401"
pass "logout revokes the session on the server"

step "10. Password recovery"
# 202 in both cases: a different answer for an unknown e-mail would be account enumeration.
code="$(status_of POST "$API/api/v1/auth/password-reset/request" "" \
  "{\"email\": \"admin-a-$SUFFIX@smoke.test\"}")"
[ "$code" = "202" ] || fail "the recovery request returned $code, expected 202"
code="$(status_of POST "$API/api/v1/auth/password-reset/request" "" \
  "{\"email\": \"nao-existe-$SUFFIX@smoke.test\"}")"
[ "$code" = "202" ] || fail "an unknown e-mail returned $code — a distinct answer allows account enumeration"
pass "the request answers 202 for both a known and an unknown e-mail"

code="$(status_of POST "$API/api/v1/auth/password-reset/confirm" "" \
  "{\"token\": \"token-que-nunca-existiu\", \"password\": \"$PASSWORD-novo\"}")"
[ "$code" = "400" ] || fail "confirmation with an invalid token returned $code, expected 400"
pass "confirmation with an invalid token is refused"

step "11. Invitations"
invitation="$(call POST "$API/api/v1/invitations" "$TOKEN_A" "{
  \"name\": \"Convidado Smoke\",
  \"email\": \"convidado-$SUFFIX@smoke.test\",
  \"role\": \"ANALYST\"
}")" || fail "invitation creation failed"
INVITATION_ID="$(printf '%s' "$invitation" | jq -r '.id')"
[ "$(printf '%s' "$invitation" | jq -r '.status')" = "PENDING" ] || fail "the invitation was not born PENDING"
# The invitation token never comes back through the API: returning it would allow taking over
# the invitee's account.
printf '%s' "$invitation" | jq -e 'has("token") or has("tokenHash")' >/dev/null \
  && fail "the invitation response carries the token"
pass "invitation $INVITATION_ID created, without exposing the token"

invitations="$(call GET "$API/api/v1/invitations" "$TOKEN_A")" || fail "invitation listing failed"
printf '%s' "$invitations" | jq -e ".[] | select(.id == $INVITATION_ID)" >/dev/null \
  || fail "the created invitation does not appear in the listing"
pass "the invitation appears in the company listing"

code="$(status_of GET "$API/api/v1/invitations/accept?token=token-que-nunca-existiu")"
[ "$code" = "400" ] || fail "preview with an invalid token returned $code, expected 400"
pass "the public preview refuses an invalid token"

code="$(status_of DELETE "$API/api/v1/invitations/$INVITATION_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "revocation returned $code, expected 204"
pass "invitation revoked"

step "12. User administration"
users="$(call GET "$API/api/v1/users" "$TOKEN_A")" || fail "user listing failed"
printf '%s' "$users" | jq -e ".[] | select(.id == $USER_A)" >/dev/null \
  || fail "the administrator themselves does not appear in the listing"
pass "the user listing responds"

self="$(call GET "$API/api/v1/users/$USER_A" "$TOKEN_A")" || fail "GET /users/{id} failed"
[ "$(printf '%s' "$self" | jq -r '.email')" = "admin-a-$SUFFIX@smoke.test" ] \
  || fail "GET /users/{id} returned a different user"
pass "GET /users/{id} consistent"

renamed="$(call PATCH "$API/api/v1/users/$USER_A" "$TOKEN_A" "{\"name\": \"Admin A Renomeado\"}")" \
  || fail "PATCH /users/{id} failed"
[ "$(printf '%s' "$renamed" | jq -r '.name')" = "Admin A Renomeado" ] || fail "the name was not stored"
pass "PATCH /users/{id} changes the name"

# Company A has exactly one administrator — the caller themselves. Demoting or deactivating
# them would leave the company with nobody able to administer it, and the backend refuses with
# 409.
code="$(status_of PATCH "$API/api/v1/users/$USER_A/role" "$TOKEN_A" "{\"role\": \"VIEWER\"}")"
[ "$code" = "409" ] || fail "demoting the last ADMIN returned $code, expected 409"
code="$(status_of PATCH "$API/api/v1/users/$USER_A/active" "$TOKEN_A" "{\"active\": false}")"
[ "$code" = "409" ] || fail "deactivating the last ADMIN returned $code, expected 409"
pass "the last active administrator can neither demote nor deactivate themselves"

step "13. CSV export"
csv="$(curl -s -H "Authorization: Bearer $TOKEN_A" "$API/api/v1/vulnerabilities/export")"
printf '%s' "$csv" | head -n1 | grep -q 'severidade' || fail "CSV without the expected header row"
printf '%s' "$csv" | grep -q "SQL injection no endpoint de busca" \
  || fail "the CSV does not contain the created vulnerability"
pass "the export returns the header and the created row"

filtered="$(curl -s -H "Authorization: Bearer $TOKEN_A" \
  "$API/api/v1/vulnerabilities/export?severity=LOW")"
printf '%s' "$filtered" | grep -q "SQL injection no endpoint de busca" \
  && fail "the severity=LOW filter returned a CRITICAL vulnerability"
pass "the export filter is the same as the listing filter"

code="$(status_of GET "$API/api/v1/vulnerabilities/export" "$TOKEN_B")"
[ "$code" = "200" ] || fail "company B export returned $code"
other="$(curl -s -H "Authorization: Bearer $TOKEN_B" "$API/api/v1/vulnerabilities/export")"
printf '%s' "$other" | grep -q "SQL injection no endpoint de busca" \
  && fail "company B's export contains company A's data"
pass "export isolated between companies"

step "14. Attachments"
EVIDENCE="$(mktemp -t smoke-evidencia-XXXXXX.pdf)"
DOWNLOADED="$(mktemp -t smoke-download-XXXXXX.pdf)"
REPORT="$(mktemp -t smoke-relatorio-XXXXXX.pdf)"
trap 'rm -f "$EVIDENCE" "$DOWNLOADED" "$REPORT"' EXIT
printf '%%PDF-1.7\nevidencia do smoke test\n%%%%EOF\n' > "$EVIDENCE"

attachment="$(curl -s -H "Authorization: Bearer $TOKEN_A" -F "file=@$EVIDENCE" \
  "$API/api/v1/vulnerabilities/$VULN_ID/attachments")"
ATTACHMENT_ID="$(printf '%s' "$attachment" | jq -r '.id')"
[ -n "$ATTACHMENT_ID" ] && [ "$ATTACHMENT_ID" != "null" ] || fail "attachment upload failed: $attachment"
[ "$(printf '%s' "$attachment" | jq -r '.contentType')" = "application/pdf" ] \
  || fail "the attachment type was not detected from the bytes"
pass "attachment $ATTACHMENT_ID uploaded and recognised as application/pdf"

listed="$(call GET "$API/api/v1/vulnerabilities/$VULN_ID/attachments" "$TOKEN_A")" \
  || fail "attachment listing failed"
printf '%s' "$listed" | jq -e ".[] | select(.id == $ATTACHMENT_ID)" >/dev/null \
  || fail "the attachment does not appear in the listing"
pass "the attachment appears in the vulnerability listing"

curl -s -H "Authorization: Bearer $TOKEN_A" -o "$DOWNLOADED" \
  "$API/api/v1/vulnerabilities/$VULN_ID/attachments/$ATTACHMENT_ID/download"
cmp -s "$EVIDENCE" "$DOWNLOADED" || fail "the downloaded file differs from the uploaded one"
pass "download returns exactly the bytes that were uploaded"

# Cross-tenant is 404, never 403: a 403 would confirm that the id exists somewhere.
code="$(status_of GET "$API/api/v1/vulnerabilities/$VULN_ID/attachments" "$TOKEN_B")"
[ "$code" = "404" ] || fail "company B listed company A's attachments (HTTP $code, expected 404)"
code="$(status_of GET "$API/api/v1/vulnerabilities/$VULN_ID/attachments/$ATTACHMENT_ID/download" "$TOKEN_B")"
[ "$code" = "404" ] || fail "company B downloaded company A's attachment (HTTP $code, expected 404)"
pass "attachments isolated between companies, with 404"

code="$(status_of DELETE "$API/api/v1/vulnerabilities/$VULN_ID/attachments/$ATTACHMENT_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "attachment removal returned $code, expected 204"
pass "attachment removed"

step "15. Executive report"
report_code="$(curl -s -o "$REPORT" -w '%{http_code}' -H "Authorization: Bearer $TOKEN_A" \
  "$API/api/v1/reports/executive")"
[ "$report_code" = "200" ] || fail "the executive report returned $report_code"
[ "$(head -c 5 "$REPORT")" = "%PDF-" ] || fail "the report does not start with %PDF-"
[ "$(wc -c < "$REPORT")" -gt 1000 ] || fail "the report has an implausible size"
pass "the executive report is a PDF with content"

step "16. Scan report import"

# A scan report arrives as a file and becomes a **proposal**, not a vulnerability: the upload
# only stages the findings, and what creates anything is the confirmation. Each format is
# uploaded twice on purpose. The second time is what proves, against the real database, that the
# fingerprint of an already imported finding marks it as a duplicate instead of creating a
# second copy of something someone is already working on.

SCAN_DIR="$(mktemp -d -t smoke-scan-XXXXXX)"
# Replaces the section 14 trap and repeats what it did: a new trap does not accumulate, it
# replaces.
trap 'rm -f "$EVIDENCE" "$DOWNLOADED" "$REPORT"; rm -rf "$SCAN_DIR"' EXIT

# A finding's target is matched against the `identifier` of an asset **of the chosen project**,
# so the reports below point at the identifiers created in section 4. ZAP and nuclei report a
# URL, and it is the whole URL that has to match the identifier — hence this asset.
WEB_IDENTIFIER="https://web-$SUFFIX.smoke.test"
web_asset="$(call POST "$API/api/v1/assets" "$TOKEN_A" "{
  \"projectId\": $PROJECT_ID,
  \"name\": \"Portal web\",
  \"type\": \"WEBSITE\",
  \"identifier\": \"$WEB_IDENTIFIER\",
  \"environment\": \"PRODUCTION\",
  \"criticality\": \"HIGH\"
}")" || fail "web asset creation failed"
WEB_ASSET_ID="$(printf '%s' "$web_asset" | jq -r '.id')"
pass "web asset $WEB_ASSET_ID created for the URL-shaped targets"

backlog_before="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listing failed"
BACKLOG_BEFORE="$(printf '%s' "$backlog_before" | jq -r '.totalElements')"

# --- nmap: matches one target, leaves another unmatched, and ignores open ports ---
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
  || fail "the nmap report upload failed"
NMAP_ID="$(printf '%s' "$nmap_import" | jq -r '.id')"
[ "$(printf '%s' "$nmap_import" | jq -r '.status')" = "PENDING" ] || fail "the import was not born PENDING"
# Three script results and two open ports in the file: only the scripts become findings.
# An open port is not a vulnerability, and importing it would fill the backlog with noise.
[ "$(printf '%s' "$nmap_import" | jq -r '.totalFindings')" = "3" ] \
  || fail "nmap should yield 3 findings (NSE script results only)"
[ "$(printf '%s' "$nmap_import" | jq -r '.matchedCount')" = "2" ] || fail "wrong counter of findings with an asset"
[ "$(printf '%s' "$nmap_import" | jq -r '.unmatchedCount')" = "1" ] || fail "wrong counter of findings without an asset"
[ "$(printf '%s' "$nmap_import" | jq -r '.duplicateCount')" = "0" ] || fail "a first-time import brought duplicates"
pass "import $NMAP_ID created pending: 3 findings, 2 with an asset, 1 without"

# The upload creates nothing: it is only a proposal until someone confirms.
backlog_staged="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listing failed"
[ "$(printf '%s' "$backlog_staged" | jq -r '.totalElements')" = "$BACKLOG_BEFORE" ] \
  || fail "the report upload created a vulnerability before confirmation"
pass "nothing was created by the upload"

# A target that is not in the inventory waits for a person: the importer never creates an asset.
FINDING_ID="$(printf '%s' "$nmap_import" | jq -r '[.findings[] | select(.status == "UNMATCHED")][0].id')"
mapped="$(call PATCH "$API/api/v1/scan-imports/$NMAP_ID/findings/$FINDING_ID" "$TOKEN_A" \
  "{\"assetId\": $ASSET_ID}")" || fail "mapping the finding failed"
[ "$(printf '%s' "$mapped" | jq -r '.status')" = "MATCHED" ] || fail "the mapped finding did not become MATCHED"
[ "$(printf '%s' "$mapped" | jq -r '.assetId')" = "$ASSET_ID" ] || fail "the finding's asset was not stored"
preview="$(call GET "$API/api/v1/scan-imports/$NMAP_ID" "$TOKEN_A")" || fail "preview failed"
[ "$(printf '%s' "$preview" | jq -r '.matchedCount')" = "3" ] || fail "the mapping did not recount the import"
[ "$(printf '%s' "$preview" | jq -r '.unmatchedCount')" = "0" ] || fail "there is still a finding without an asset"
pass "finding $FINDING_ID mapped by hand and counters recomputed"

# The trail gets one row per import, not one per created vulnerability: that is what keeps the
# audit readable after a report with four hundred findings.
creates_before="$(call GET "$API/api/v1/audit-logs?size=1&entityType=Vulnerability&action=CREATE" "$TOKEN_A")" \
  || fail "audit query failed"
CREATES_BEFORE="$(printf '%s' "$creates_before" | jq -r '.totalElements')"

confirmed="$(call POST "$API/api/v1/scan-imports/$NMAP_ID/confirm" "$TOKEN_A")" || fail "confirmation failed"
[ "$(printf '%s' "$confirmed" | jq -r '.status')" = "CONFIRMED" ] || fail "the import did not become CONFIRMED"
[ "$(printf '%s' "$confirmed" | jq -r '.importedCount')" = "3" ] || fail "3 vulnerabilities were not created"
[ "$(printf '%s' "$confirmed" | jq -r '.skippedCount')" = "0" ] || fail "some finding was skipped for no reason"
[ "$(printf '%s' "$confirmed" | jq '[.findings[] | select(.status == "IMPORTED" and (.vulnerabilityId | type) == "number")] | length')" = "3" ] \
  || fail "some imported finding does not point at the created vulnerability"
backlog_after="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listing failed"
[ "$(printf '%s' "$backlog_after" | jq -r '.totalElements')" = "$((BACKLOG_BEFORE + 3))" ] \
  || fail "the backlog did not grow by exactly 3 vulnerabilities"
pass "confirmation created 3 vulnerabilities, one per finding with an asset"

creates_after="$(call GET "$API/api/v1/audit-logs?size=1&entityType=Vulnerability&action=CREATE" "$TOKEN_A")" \
  || fail "audit query failed"
[ "$(printf '%s' "$creates_after" | jq -r '.totalElements')" = "$CREATES_BEFORE" ] \
  || fail "confirmation wrote a CREATE per vulnerability and buried the trail"
scan_audit="$(call GET "$API/api/v1/audit-logs?size=20&entityType=ScanImport&action=SCAN_IMPORT" "$TOKEN_A")" \
  || fail "audit query failed"
printf '%s' "$scan_audit" | jq -e ".content[] | select(.entityId == $NMAP_ID)" >/dev/null \
  || fail "confirmation left no SCAN_IMPORT row in the audit trail"
[ "$(printf '%s' "$scan_audit" | jq "[.content[] | select(.entityId == $NMAP_ID)] | length")" = "1" ] \
  || fail "confirmation left more than one audit row"
pass "the audit trail has exactly one SCAN_IMPORT row and no CREATE per finding"

# A closed import does not go back: both paths answer 409 and not 400, because the request is
# well formed — what is wrong is the state of the row.
code="$(status_of POST "$API/api/v1/scan-imports/$NMAP_ID/confirm" "$TOKEN_A")"
[ "$code" = "409" ] || fail "the second confirmation returned $code, expected 409"
code="$(status_of DELETE "$API/api/v1/scan-imports/$NMAP_ID" "$TOKEN_A")"
[ "$code" = "409" ] || fail "discarding a confirmed import returned $code, expected 409"
pass "only a pending import can be confirmed or discarded"

# The same file again: no new findings, all marked as already recorded. What this protects is
# the human work — the vulnerability from the first import may already have a status, an
# assignee and a discussion, and re-importing must not undo any of that.
nmap_again="$(scan_upload "$PROJECT_ID" NMAP_XML "$SCAN_DIR/nmap.xml" "$TOKEN_A")" \
  || fail "the second nmap report upload failed"
NMAP_AGAIN_ID="$(printf '%s' "$nmap_again" | jq -r '.id')"
[ "$(printf '%s' "$nmap_again" | jq -r '.totalFindings')" = "3" ] || fail "the report changed size"
[ "$(printf '%s' "$nmap_again" | jq -r '.duplicateCount')" = "3" ] \
  || fail "the re-import did not recognise every finding as a duplicate"
[ "$(printf '%s' "$nmap_again" | jq -r '.matchedCount')" = "0" ] || fail "duplicate should win over matched"
[ "$(printf '%s' "$nmap_again" | jq -r '.unmatchedCount')" = "0" ] || fail "wrong unmatched counter"
confirmed_again="$(call POST "$API/api/v1/scan-imports/$NMAP_AGAIN_ID/confirm" "$TOKEN_A")" \
  || fail "confirmation of the re-import failed"
[ "$(printf '%s' "$confirmed_again" | jq -r '.importedCount')" = "0" ] || fail "the re-import created a vulnerability"
[ "$(printf '%s' "$confirmed_again" | jq -r '.skippedCount')" = "3" ] || fail "the duplicates were not skipped"
backlog_dup="$(call GET "$API/api/v1/vulnerabilities?size=1" "$TOKEN_A")" || fail "listing failed"
[ "$(printf '%s' "$backlog_dup" | jq -r '.totalElements')" = "$((BACKLOG_BEFORE + 3))" ] \
  || fail "re-importing the same report changed the size of the backlog"
pass "re-import of the same file: 3 duplicates, 0 created"

# --- ZAP: one instance per alert, rounded CVSS and the first CVE in the list ---
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
  || fail "the ZAP report upload failed"
ZAP_ID="$(printf '%s' "$zap_import" | jq -r '.id')"
[ "$(printf '%s' "$zap_import" | jq -r '.totalFindings')" = "2" ] || fail "ZAP should yield 2 findings"
[ "$(printf '%s' "$zap_import" | jq -r '.matchedCount')" = "2" ] || fail "both alerts should find the web asset"
pass "import $ZAP_ID created: 2 findings, both with an asset"

zap_confirmed="$(call POST "$API/api/v1/scan-imports/$ZAP_ID/confirm" "$TOKEN_A")" \
  || fail "the ZAP confirmation failed"
[ "$(printf '%s' "$zap_confirmed" | jq -r '.importedCount')" = "2" ] || fail "ZAP did not create 2 vulnerabilities"
LOG4SHELL_ID="$(printf '%s' "$zap_confirmed" | jq -r '[.findings[] | select(.ruleId == "10038")][0].vulnerabilityId')"
log4shell="$(call GET "$API/api/v1/vulnerabilities/$LOG4SHELL_ID" "$TOKEN_A")" || fail "reading the vulnerability failed"
# 7.53 does not fit in NUMERIC(3,1): the normaliser rounds to 7.5 instead of letting the
# database refuse the whole row at confirmation.
[ "$(printf '%s' "$log4shell" | jq -r '.cvssScore')" = "7.5" ] || fail "the ZAP CVSS was not normalised to 7.5"
# One CVE per finding, and it is the first well-formed one in the list ZAP sent.
[ "$(printf '%s' "$log4shell" | jq -r '.cve')" = "CVE-2021-44228" ] || fail "the ZAP CVE was not extracted"
[ "$(printf '%s' "$log4shell" | jq -r '.severity')" = "HIGH" ] || fail "riskcode 3 should become HIGH"
pass "vulnerability $LOG4SHELL_ID created with CVSS 7.5, CVE-2021-44228 and severity HIGH"

zap_again="$(scan_upload "$PROJECT_ID" ZAP_JSON "$SCAN_DIR/zap.json" "$TOKEN_A")" \
  || fail "the second ZAP report upload failed"
ZAP_AGAIN_ID="$(printf '%s' "$zap_again" | jq -r '.id')"
[ "$(printf '%s' "$zap_again" | jq -r '.duplicateCount')" = "2" ] \
  || fail "the ZAP re-import did not mark every finding as a duplicate"
pass "ZAP re-import: 2 duplicates, 0 with an asset"

# Discarding throws the proposal away; the finding rows stay, and the file on disk goes.
code="$(status_of DELETE "$API/api/v1/scan-imports/$ZAP_AGAIN_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "discarding returned $code, expected 204"
discarded="$(call GET "$API/api/v1/scan-imports/$ZAP_AGAIN_ID" "$TOKEN_A")" || fail "the preview of the discarded import failed"
[ "$(printf '%s' "$discarded" | jq -r '.status')" = "DISCARDED" ] || fail "the import did not become DISCARDED"
[ "$(printf '%s' "$discarded" | jq '.findings | length')" = "2" ] \
  || fail "discarding deleted what the report found"
pass "import $ZAP_AGAIN_ID discarded, keeping what the report found"

# --- nuclei: one JSON per line, and the line that is not JSON is skipped ------
cat > "$SCAN_DIR/nuclei.jsonl" <<JSONL
{"template-id":"springboot-actuators","info":{"name":"Spring Boot Actuator Exposure","description":"Actuator exposto sem autenticacao.","severity":"high","classification":{"cvss-score":8.6,"cve-id":["CVE-2023-1234"]}},"host":"$WEB_IDENTIFIER","matched-at":"$WEB_IDENTIFIER","timestamp":"2023-11-13T10:17:00Z"}
nuclei: connection reset by peer while writing this line
{"template-id":"tech-detect","info":{"name":"Wappalyzer Technology Detection","description":"Identifica tecnologias expostas.","severity":"info"},"host":"$WEB_IDENTIFIER","matched-at":"$WEB_IDENTIFIER","timestamp":"2023-11-13T10:16:30Z"}
JSONL

nuclei_import="$(scan_upload "$PROJECT_ID" NUCLEI_JSONL "$SCAN_DIR/nuclei.jsonl" "$TOKEN_A")" \
  || fail "the nuclei report upload failed"
NUCLEI_ID="$(printf '%s' "$nuclei_import" | jq -r '.id')"
# Three lines in the file, two become findings: a corrupted line in the middle of the stream
# must not bring the whole report down.
[ "$(printf '%s' "$nuclei_import" | jq -r '.totalFindings')" = "2" ] \
  || fail "nuclei should yield 2 findings and skip the line that is not JSON"
[ "$(printf '%s' "$nuclei_import" | jq -r '.matchedCount')" = "2" ] || fail "both findings should find the web asset"
pass "import $NUCLEI_ID created: 2 findings, corrupted line ignored"

nuclei_confirmed="$(call POST "$API/api/v1/scan-imports/$NUCLEI_ID/confirm" "$TOKEN_A")" \
  || fail "the nuclei confirmation failed"
[ "$(printf '%s' "$nuclei_confirmed" | jq -r '.importedCount')" = "2" ] || fail "nuclei did not create 2 vulnerabilities"
pass "the nuclei confirmation created 2 vulnerabilities"

nuclei_again="$(scan_upload "$PROJECT_ID" NUCLEI_JSONL "$SCAN_DIR/nuclei.jsonl" "$TOKEN_A")" \
  || fail "the second nuclei report upload failed"
NUCLEI_AGAIN_ID="$(printf '%s' "$nuclei_again" | jq -r '.id')"
[ "$(printf '%s' "$nuclei_again" | jq -r '.duplicateCount')" = "2" ] \
  || fail "the nuclei re-import did not mark every finding as a duplicate"
code="$(status_of DELETE "$API/api/v1/scan-imports/$NUCLEI_AGAIN_ID" "$TOKEN_A")"
[ "$code" = "204" ] || fail "discarding the nuclei re-import returned $code, expected 204"
pass "nuclei re-import: 2 duplicates, discarded afterwards"

# --- the per-file ceiling -----------------------------------------------------
# Import is synchronous, and it is this ceiling that keeps it so: above it the upload is
# refused before anything is written, instead of the request becoming a job of minutes.
awk -v host="$WEB_IDENTIFIER" 'BEGIN {
  for (i = 1; i <= 2001; i++)
    printf "{\"template-id\":\"limite-%d\",\"info\":{\"name\":\"Achado %d\",\"severity\":\"low\"},\"matched-at\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n", i, i, host
}' > "$SCAN_DIR/nuclei-grande.jsonl"

history_before="$(call GET "$API/api/v1/scan-imports?size=1" "$TOKEN_A")" || fail "history failed"
HISTORY_BEFORE="$(printf '%s' "$history_before" | jq -r '.totalElements')"
code="$(scan_upload_status "$PROJECT_ID" NUCLEI_JSONL "$SCAN_DIR/nuclei-grande.jsonl" "$TOKEN_A")"
[ "$code" = "400" ] || fail "a report above the ceiling returned $code, expected 400"
history_after="$(call GET "$API/api/v1/scan-imports?size=1" "$TOKEN_A")" || fail "history failed"
[ "$(printf '%s' "$history_after" | jq -r '.totalElements')" = "$HISTORY_BEFORE" ] \
  || fail "the refused report left an import in the history"
pass "a report above the ceiling is refused without leaving any row"

# --- history ------------------------------------------------------------------
history="$(call GET "$API/api/v1/scan-imports?size=50" "$TOKEN_A")" || fail "history failed"
printf '%s' "$history" | jq -e ".content[] | select(.id == $NMAP_ID and .status == \"CONFIRMED\")" >/dev/null \
  || fail "the confirmed import does not appear in the history"
printf '%s' "$history" | jq -e ".content[] | select(.id == $ZAP_AGAIN_ID and .status == \"DISCARDED\")" >/dev/null \
  || fail "the discarded import does not appear in the history"
# The listing carries the counters and not the findings: a page of twenty imports with all the
# findings of each one would be thousands of rows to draw six numbers.
printf '%s' "$history" | jq -e '.content[0] | has("findings")' >/dev/null \
  && fail "the history is loading the findings of each import"
pass "the history lists the imports with the counters, without the findings"

# --- isolation between companies ----------------------------------------------
# 404 and never 403, like the rest of the API: a 403 would confirm that the id exists.
for path in "/scan-imports/$NMAP_ID" "/scan-imports/$ZAP_ID"; do
  code="$(status_of GET "$API/api/v1$path" "$TOKEN_B")"
  [ "$code" = "404" ] || fail "company B read company A's $path (HTTP $code, expected 404)"
done
code="$(status_of DELETE "$API/api/v1/scan-imports/$NMAP_ID" "$TOKEN_B")"
[ "$code" = "404" ] || fail "company B discarded company A's import (HTTP $code, expected 404)"
code="$(scan_upload_status "$PROJECT_ID" NMAP_XML "$SCAN_DIR/nmap.xml" "$TOKEN_B")"
[ "$code" = "404" ] || fail "company B imported into a project of company A (HTTP $code, expected 404)"
history_b="$(call GET "$API/api/v1/scan-imports?size=50" "$TOKEN_B")" || fail "company B history failed"
printf '%s' "$history_b" | jq -e ".content[] | select(.id == $NMAP_ID)" >/dev/null \
  && fail "company B's history exposed an import of company A"
pass "imports isolated between companies, with 404"

printf '\n\033[32mSmoke test completed successfully.\033[0m\n'
