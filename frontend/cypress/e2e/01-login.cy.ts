import { demoPassword } from '../support/commands';

/**
 * O único arquivo desta suíte que passa pelo formulário de login. Todos os outros autenticam
 * pela API com `cy.loginAs()`, pela razão dita em `support/e2e.ts`: se o formulário quebrar,
 * exatamente um arquivo fica vermelho e o motivo está na primeira linha do relatório.
 */

const ROLES = [
  { email: 'admin@demo.test', role: 'Administrador' },
  { email: 'analyst@demo.test', role: 'Analista' },
  { email: 'developer@demo.test', role: 'Desenvolvedor' },
  { email: 'viewer@demo.test', role: 'Leitor' },
];

describe('Login', () => {
  beforeEach(() => {
    cy.visit('/login');
  });

  ROLES.forEach(({ email, role }) => {
    it(`autentica ${email} e abre o dashboard com o papel ${role}`, () => {
      cy.byTestId('login-email').type(email);
      cy.byTestId('login-password').type(demoPassword(), { log: false });
      cy.byTestId('login-submit').click();

      cy.location('pathname').should('eq', '/dashboard');
      // O nome e o papel vêm da sessão devolvida pelo backend, não de nada montado na tela.
      cy.get('.layout__user-trigger').should('be.visible').click();
      cy.get('.layout__user-info-meta').should('contain.text', role);
      // Fecha o menu para não deixar o overlay sobre a próxima asserção.
      cy.get('body').type('{esc}');

      cy.window().then((win) => {
        expect(win.localStorage.getItem('securityhub.accessToken')).to.be.a('string').and.not.be
          .empty;
        expect(win.localStorage.getItem('securityhub.refreshToken')).to.be.a('string').and.not.be
          .empty;
        expect(JSON.parse(win.localStorage.getItem('securityhub.currentUser') ?? '{}').email).to.eq(
          email,
        );
      });
    });
  });

  it('recusa a senha errada sem dizer se a conta existe', () => {
    cy.byTestId('login-email').type('admin@demo.test');
    cy.byTestId('login-password').type('senha-que-nao-e-a-dele', { log: false });
    cy.byTestId('login-submit').click();

    cy.get('.auth-alert').should('be.visible').and('not.be.empty');
    cy.location('pathname').should('eq', '/login');
    cy.window().then((win) => {
      expect(win.localStorage.getItem('securityhub.accessToken')).to.be.null;
    });

    // A mesma mensagem para um e-mail que nunca existiu: uma resposta diferente aqui seria
    // enumeração de contas pela tela, que é o mesmo defeito que o backend evita na API.
    cy.byTestId('login-email').clear().type('ninguem@demo.test');
    cy.byTestId('login-password').clear().type('senha-que-nao-e-a-dele', { log: false });
    cy.byTestId('login-submit').click();
    cy.get('.auth-alert').should('be.visible').and('not.be.empty');
  });

  it('leva ao destino original depois do login (returnUrl)', () => {
    // Sem sessão, o authGuard manda para /login guardando o destino.
    cy.visit('/vulnerabilities');
    cy.location('pathname').should('eq', '/login');
    cy.location('search').should('contain', 'returnUrl=%2Fvulnerabilities');

    cy.byTestId('login-email').type('analyst@demo.test');
    cy.byTestId('login-password').type(demoPassword(), { log: false });
    cy.byTestId('login-submit').click();

    cy.location('pathname').should('eq', '/vulnerabilities');
  });

  it('encerra a sessão e limpa as três chaves do armazenamento', () => {
    cy.byTestId('login-email').type('admin@demo.test');
    cy.byTestId('login-password').type(demoPassword(), { log: false });
    cy.byTestId('login-submit').click();
    cy.location('pathname').should('eq', '/dashboard');

    cy.get('.layout__user-trigger').click();
    cy.contains('.mat-mdc-menu-item', 'Sair').click();

    cy.location('pathname').should('eq', '/login');
    cy.window().then((win) => {
      // As três, e não só o access token: é o que faz o laço de renovação terminar
      // (ver o comentário de `isAuthenticated()` em AuthService).
      expect(win.localStorage.getItem('securityhub.accessToken')).to.be.null;
      expect(win.localStorage.getItem('securityhub.refreshToken')).to.be.null;
      expect(win.localStorage.getItem('securityhub.currentUser')).to.be.null;
    });

    // E o guard continua de pé: voltar para uma rota autenticada não restaura nada.
    cy.visit('/dashboard');
    cy.location('pathname').should('eq', '/login');
  });
});
