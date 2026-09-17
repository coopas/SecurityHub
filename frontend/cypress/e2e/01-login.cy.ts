import { demoPassword } from '../support/commands';

/**
 * The only file in this suite that goes through the login form. All the others authenticate
 * through the API with `cy.loginAs()`, for the reason stated in `support/e2e.ts`: if the form
 * breaks, exactly one file goes red and the reason is on the first line of the report.
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
      // The name and the role come from the session the backend returned, not from anything
      // assembled on screen.
      cy.get('.layout__user-trigger').should('be.visible').click();
      cy.get('.layout__user-info-meta').should('contain.text', role);
      // Close the menu so the overlay is not left sitting over the next assertion.
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

    // The same message for an e-mail that never existed: a different response here would be
    // account enumeration through the screen, the same defect the backend avoids on the API.
    cy.byTestId('login-email').clear().type('ninguem@demo.test');
    cy.byTestId('login-password').clear().type('senha-que-nao-e-a-dele', { log: false });
    cy.byTestId('login-submit').click();
    cy.get('.auth-alert').should('be.visible').and('not.be.empty');
  });

  it('leva ao destino original depois do login (returnUrl)', () => {
    // With no session, authGuard sends you to /login keeping the destination.
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
      // All three, and not just the access token: it is what makes the refresh loop stop
      // (see the comment on `isAuthenticated()` in AuthService).
      expect(win.localStorage.getItem('securityhub.accessToken')).to.be.null;
      expect(win.localStorage.getItem('securityhub.refreshToken')).to.be.null;
      expect(win.localStorage.getItem('securityhub.currentUser')).to.be.null;
    });

    // And the guard is still standing: going back to an authenticated route restores nothing.
    cy.visit('/dashboard');
    cy.location('pathname').should('eq', '/login');
  });
});
