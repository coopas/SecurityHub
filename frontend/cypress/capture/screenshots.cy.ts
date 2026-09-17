/**
 * Captura as imagens que o README publica.
 *
 * Não é um teste: não afirma nada e não roda na CI — fica fora de `cypress/e2e`, que é o
 * `specPattern` da suíte. Existe para que as imagens do README sejam reproduzíveis por um
 * comando em vez de dependerem de alguém lembrar quais telas fotografar, em que largura e
 * em que tema. Rode com `scripts/capture-screenshots.sh`, com a pilha do compose no ar.
 *
 * As telas são fotografadas com o tenant `demo`, cujo seed é datado de forma relativa: os
 * números mudam de um dia para o outro. Isso é irrelevante aqui, porque nada é afirmado —
 * mas é a razão de este arquivo não poder virar teste sem quebrar a regra 1 de `support/e2e.ts`.
 */

const ADMIN = 'admin@demo.test';

/** Espera a tela assentar antes de fotografar: sem isto sai um esqueleto de carregamento. */
function settle(testId: string): void {
  cy.byTestId(testId, { timeout: 30000 }).should('be.visible');
  // As fontes chegam do Google Fonts; fotografar antes delas registra a pilha de reserva.
  cy.document().its('fonts.status').should('equal', 'loaded');
  cy.wait(400);
}

/** O mesmo, para telas que não expõem um testid de contêiner: o título serve de âncora. */
function telaPronta(titulo: string): void {
  cy.contains('h1', titulo, { timeout: 30000 }).should('be.visible');
  cy.get('.sh-state', { timeout: 30000 }).should('not.exist');
  cy.document().its('fonts.status').should('equal', 'loaded');
  cy.wait(500);
}

/**
 * Fixa o tema e recarrega.
 *
 * Chamado também para o claro, e não só para o escuro: sem escolha salva o `index.html`
 * segue o `prefers-color-scheme`, e o navegador headless desta captura responde "escuro" —
 * as imagens do tema claro sairiam escuras. Aqui a preferência do sistema é justamente o
 * que não se quer, porque o README precisa das duas versões independentemente da máquina.
 */
function useTheme(theme: 'light' | 'dark'): void {
  cy.window().then((win) => win.localStorage.setItem('securityhub.theme', theme));
  cy.reload();
}

// A janela do Electron headless é fixa em 1280x720 e a `viewportWidth` do `cypress.config.ts`
// (1400) venceria a passada por `--config`. A aplicação renderizaria a 1400 enquanto a foto
// recorta 1280: sai cortada à direita, com os sete cartões do dashboard espremidos num
// espaço que, na tela de verdade, comporta cinco. Fixar aqui é o que garante que a imagem
// mostre o mesmo que um navegador de 1280px mostra.
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
    // 834px é o iPad retrato: a largura em que a navegação vira gaveta e a grade se rearranja.
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
