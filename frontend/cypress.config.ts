import { defineConfig } from 'cypress';

/**
 * A suíte roda contra a pilha do `docker compose`, em http://localhost:8081, e não contra
 * `ng serve`.
 *
 * O motivo é o que está sendo testado. O job `compose` da CI já sobe backend, banco e
 * frontend com `--wait` e com o seed do perfil `demo`; apontar o Cypress para ele exercita o
 * bundle de produção servido pelo nginx real, com o proxy de `/api` que existe só ali. Um
 * `ng serve` testaria um bundle de desenvolvimento e um proxy que não vai para produção —
 * seria a camada errada.
 *
 * `baseUrl` é sobrescrevível por `CYPRESS_BASE_URL`, sem nenhum código aqui: o Cypress
 * converte toda variável `CYPRESS_*` cujo nome casa com uma chave de configuração na própria
 * configuração. As que não casam — `CYPRESS_DEMO_PASSWORD` — chegam em `Cypress.env()`.
 */
export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:8081',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',
    fixturesFolder: 'cypress/fixtures',
    screenshotsFolder: 'cypress/screenshots',
    videosFolder: 'cypress/videos',
    downloadsFolder: 'cypress/downloads',
    video: true,
    viewportWidth: 1400,
    viewportHeight: 900,

    /**
     * Duas tentativas no modo headless, nenhuma no modo interativo. O alvo é uma pilha real:
     * o backend acabou de subir, o primeiro acesso a uma rota lazy baixa um chunk, e uma
     * falha isolada por esse tipo de latência não é um defeito do produto. No modo
     * interativo a repetição só esconderia do desenvolvedor o passo que falhou.
     */
    retries: { runMode: 2, openMode: 0 },

    /** O backend faz BCrypt com custo 12: um login leva algumas centenas de milissegundos. */
    defaultCommandTimeout: 12000,
    requestTimeout: 15000,
    responseTimeout: 30000,
    pageLoadTimeout: 60000,
  },

  env: {
    /**
     * Senha pública das contas do seed `demo`, a mesma de `docker-compose.yml`. Não é
     * segredo: o tenant `demo` só existe no banco que o compose acabou de criar na máquina de
     * quem clonou o repositório. Sobrescrevível por `CYPRESS_DEMO_PASSWORD`.
     */
    DEMO_PASSWORD: 'Demo@SecurityHub2026',
  },
});
