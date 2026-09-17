/**
 * Captures the images the README publishes.
 *
 * It is not a test: it asserts nothing and does not run in CI — it sits outside `cypress/e2e`,
 * which is the suite's `specPattern`. It exists so that the README images are reproducible by
 * one command instead of depending on someone remembering which screens to photograph, at what
 * width and in what theme. Run it with `scripts/capture-screenshots.sh`, with the compose stack
 * up.
 *
 * The screens are photographed with the `demo` tenant, whose seed is dated relatively: the
 * numbers change from one day to the next. That is irrelevant here, because nothing is asserted
 * — but it is the reason this file cannot become a test without breaking rule 1 of
 * `support/e2e.ts`.
 */

const ADMIN = 'admin@demo.test';

/** Waits for the screen to settle before photographing: without this you get a loading skeleton. */
function settle(testId: string): void {
  cy.byTestId(testId, { timeout: 30000 }).should('be.visible');
  // The fonts come from Google Fonts; photographing before them records the fallback stack.
  cy.document().its('fonts.status').should('equal', 'loaded');
  cy.wait(400);
}

/** The same, for screens that expose no container testid: the heading serves as the anchor. */
function telaPronta(titulo: string): void {
  cy.contains('h1', titulo, { timeout: 30000 }).should('be.visible');
  cy.get('.sh-state', { timeout: 30000 }).should('not.exist');
  cy.document().its('fonts.status').should('equal', 'loaded');
  cy.wait(500);
}

/**
 * Pins the theme and reloads.
 *
 * Called for the light theme too, and not only for the dark one: with no saved choice
 * `index.html` follows `prefers-color-scheme`, and the headless browser of this capture answers
 * "dark" — the light theme images would come out dark. Here the system preference is precisely
 * what is not wanted, because the README needs both versions regardless of the machine.
 */
function useTheme(theme: 'light' | 'dark'): void {
  cy.window().then((win) => win.localStorage.setItem('securityhub.theme', theme));
  cy.reload();
}

// The headless Electron window is fixed at 1280x720 and the `viewportWidth` from
// `cypress.config.ts` (1400) would win over the one passed with `--config`. The application
// would render at 1400 while the photo crops at 1280: it comes out cut off on the right, with
// the dashboard's seven cards squeezed into a space that, on the real screen, fits five. Pinning
// it here is what guarantees the image shows the same as a 1280px browser shows.
const LARGURA = 1280;
const ALTURA = 720;

describe('capturas do README', () => {
  beforeEach(() => {
    cy.viewport(LARGURA, ALTURA);
  });
  it('login, nos dois temas', () => {
    cy.visit('/login');
    useTheme('light');
    cy.contains('button', 'Entrar').should('be.visible');
    cy.document().its('fonts.status').should('equal', 'loaded');
    cy.screenshot('login', { capture: 'viewport', overwrite: true });

    useTheme('dark');
    cy.contains('button', 'Entrar').should('be.visible');
    cy.screenshot('login-dark', { capture: 'viewport', overwrite: true });
    useTheme('light');
  });

  it('dashboard, nos dois temas e em tablet', () => {
    cy.loginAs(ADMIN, '/dashboard');
    useTheme('light');
    settle('summary-cards');
    cy.screenshot('dashboard', { capture: 'viewport', overwrite: true });

    useTheme('dark');
    settle('summary-cards');
    cy.screenshot('dashboard-dark', { capture: 'viewport', overwrite: true });

    useTheme('light');
    // 834px is the iPad in portrait: the width where the navigation turns into a drawer and the
    // grid rearranges itself.
    cy.viewport(834, 1112);
    settle('summary-cards');
    cy.screenshot('dashboard-tablet', { capture: 'viewport', overwrite: true });
  });

  it('as listagens', () => {
    cy.loginAs(ADMIN, '/vulnerabilities');
    useTheme('light');
    telaPronta('Vulnerabilidades');
    cy.screenshot('vulnerabilities', { capture: 'viewport', overwrite: true });

    cy.visit('/assets');
    telaPronta('Ativos');
    cy.screenshot('assets', { capture: 'viewport', overwrite: true });

    cy.visit('/projects');
    telaPronta('Projetos');
    cy.screenshot('projects', { capture: 'viewport', overwrite: true });

    cy.visit('/audit');
    telaPronta('Auditoria');
    cy.screenshot('audit', { capture: 'viewport', overwrite: true });

    cy.visit('/imports');
    telaPronta('Importações');
    cy.screenshot('imports', { capture: 'viewport', overwrite: true });
  });

  it('a revisão de um achado, no tema escuro', () => {
    cy.loginAs(ADMIN, '/vulnerabilities');
    telaPronta('Vulnerabilidades');
    useTheme('dark');
    telaPronta('Vulnerabilidades');
    cy.screenshot('vulnerabilities-dark', { capture: 'viewport', overwrite: true });
    useTheme('light');
  });
});
