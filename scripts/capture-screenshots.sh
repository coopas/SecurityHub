#!/usr/bin/env bash
#
# Regenerates the images the README publishes, from the running compose stack.
#
# The captures are not part of the suite: `cypress/capture/` sits outside the `specPattern` of
# `cypress.config.ts` on purpose, so it does not run in CI and does not count as a test. What
# this script guarantees is that the README images can be remade with one command, instead of
# someone having to remember which screens to photograph, at which width and in which theme.
#
#   ./scripts/capture-screenshots.sh
#
# Requires the stack to be up (`docker compose up -d --wait`) and Node 18 (see frontend/.nvmrc).

set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend="$raiz/frontend"
destino="$raiz/docs/screenshots"
origem="$frontend/cypress/screenshots/screenshots.cy.ts"

base_url="${CYPRESS_BASE_URL:-http://localhost:8081}"

echo "==> Checking the stack at $base_url"
if ! curl -fsS -o /dev/null --max-time 10 "$base_url"; then
  echo "The application did not respond at $base_url." >&2
  echo "Bring the stack up first: docker compose up -d --wait" >&2
  exit 1
fi

echo "==> Capturing"
cd "$frontend"
rm -rf "$origem"
# Electron, not Chrome: it is the browser that ships with Cypress itself, so the capture
# works on any machine that already runs the suite, without depending on an installed Chrome.
#
# The viewport follows the window, not the other way round. Headless Electron opens fixed at
# 1280x720 and ignores `--window-size`; asking for 1440 inside it renders the application wider
# than the window and the shot comes out cropped on the right, with a scrollbar. That is what
# happened on the first two attempts, and it is why the number below is not arbitrary.
npx cypress run \
  --browser "${CYPRESS_BROWSER:-electron}" \
  --spec 'cypress/capture/screenshots.cy.ts' \
  --config "specPattern=cypress/capture/**/*.cy.ts,video=false,viewportWidth=1280,viewportHeight=720"

echo "==> Publishing to docs/screenshots"
mkdir -p "$destino"
# Cypress prefixes each file with the name of the test that generated it; the README references
# the clean names, so the prefix is dropped here.
find "$origem" -name '*.png' -print0 | while IFS= read -r -d '' arquivo; do
  nome="$(basename "$arquivo")"
  nome="${nome##*-- }"
  cp "$arquivo" "$destino/$nome"
  echo "    $nome"
done

echo "==> Done. Images in docs/screenshots/"
