import { defineConfig } from 'cypress';

/**
 * The suite runs against the `docker compose` stack, at http://localhost:8081, and not against
 * `ng serve`.
 *
 * The reason is what is being tested. The CI `compose` job already brings up backend, database
 * and frontend with `--wait` and with the `demo` profile seed; pointing Cypress at it exercises
 * the production bundle served by the real nginx, with the `/api` proxy that only exists there.
 * An `ng serve` would test a development bundle and a proxy that never reaches production — it
 * would be the wrong layer.
 *
 * `baseUrl` is overridable by `CYPRESS_BASE_URL`, with no code here: Cypress turns every
 * `CYPRESS_*` variable whose name matches a configuration key into that configuration itself.
 * The ones that do not match — `CYPRESS_DEMO_PASSWORD` — arrive in `Cypress.env()`.
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
     * Two retries in headless mode, none in interactive mode. The target is a real stack: the
     * backend has just come up, the first hit on a lazy route downloads a chunk, and an isolated
     * failure caused by that kind of latency is not a product defect. In interactive mode the
     * retry would only hide the failing step from the developer.
     */
    retries: { runMode: 2, openMode: 0 },

    /** The backend does BCrypt at cost 12: a login takes a few hundred milliseconds. */
    defaultCommandTimeout: 12000,
    requestTimeout: 15000,
    responseTimeout: 30000,
    pageLoadTimeout: 60000,
  },

  env: {
    /**
     * Public password of the `demo` seed accounts, the same one as in `docker-compose.yml`. It
     * is not a secret: the `demo` tenant only exists in the database compose has just created on
     * the machine of whoever cloned the repository. Overridable by `CYPRESS_DEMO_PASSWORD`.
     */
    DEMO_PASSWORD: 'Demo@SecurityHub2026',
  },
});
