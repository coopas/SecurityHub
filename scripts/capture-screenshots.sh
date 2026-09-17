#!/usr/bin/env bash
#
# Regera as imagens que o README publica, a partir da pilha do compose no ar.
#
# As capturas não fazem parte da suíte: `cypress/capture/` fica fora do `specPattern` de
# `cypress.config.ts` de propósito, para não rodar na CI nem contar como teste. O que este
# script garante é que as imagens do README possam ser refeitas por um comando, em vez de
# alguém precisar lembrar quais telas fotografar, em que largura e em que tema.
#
#   ./scripts/capture-screenshots.sh
#
# Requer a pilha no ar (`docker compose up -d --wait`) e Node 18 (ver frontend/.nvmrc).

set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend="$raiz/frontend"
destino="$raiz/docs/screenshots"
origem="$frontend/cypress/screenshots/screenshots.cy.ts"

base_url="${CYPRESS_BASE_URL:-http://localhost:8081}"

echo "==> Conferindo a pilha em $base_url"
if ! curl -fsS -o /dev/null --max-time 10 "$base_url"; then
  echo "A aplicação não respondeu em $base_url." >&2
  echo "Suba a pilha antes: docker compose up -d --wait" >&2
  exit 1
fi

echo "==> Capturando"
cd "$frontend"
rm -rf "$origem"
# Electron, e não Chrome: é o navegador que vem com o próprio Cypress, então a captura
# funciona em qualquer máquina que já rode a suíte, sem depender de um Chrome instalado.
#
# A viewport acompanha a janela, e não o contrário. O Electron headless abre fixo em
# 1280x720 e ignora `--window-size`; pedir 1440 ali dentro renderiza a aplicação mais larga
# que a janela e a foto sai cortada à direita, com barra de rolagem. Foi o que aconteceu nas
# duas primeiras tentativas, e é por isso que o número abaixo não é arbitrário.
npx cypress run \
  --browser "${CYPRESS_BROWSER:-electron}" \
  --spec 'cypress/capture/screenshots.cy.ts' \
  --config "specPattern=cypress/capture/**/*.cy.ts,video=false,viewportWidth=1280,viewportHeight=720"

echo "==> Publicando em docs/screenshots"
mkdir -p "$destino"
# O Cypress prefixa cada arquivo com o nome do teste que o gerou; o README referencia os
# nomes limpos, então o prefixo cai aqui.
find "$origem" -name '*.png' -print0 | while IFS= read -r -d '' arquivo; do
  nome="$(basename "$arquivo")"
  nome="${nome##*-- }"
  cp "$arquivo" "$destino/$nome"
  echo "    $nome"
done

echo "==> Pronto. Imagens em docs/screenshots/"
