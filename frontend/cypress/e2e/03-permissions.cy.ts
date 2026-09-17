import { AuthSession } from '../support/commands';

/**
 * The VIEWER role's permissions, checked on both layers where they exist.
 *
 * The top half looks at the screen: no write affordance, and `/403` on direct navigation. The
 * bottom half calls the API with the VIEWER's own token and demands a 403.
 *
 * Both are necessary, and it is the bottom one that matters. Hiding a button is not a control —
 * it is convenience; whoever wants to write does not need the button, they need a `curl`. A
 * suite that only checked the CSS would be testing the wrong layer and would pass untouched with
 * the backend completely open. It is the very principle this project applies on the backend, and
 * it holds here too.
 */
describe('VIEWER não escreve, nem pela tela nem pela API', () => {
  let viewer: AuthSession;
  let vulnerabilityId: number;

  before(() => {
    cy.loginAs('viewer@demo.test').then((session) => {
      viewer = session;
      // Reading is allowed to every role, so the VIEWER's own session serves to find an
      // existing target from their company — which is what makes a 403 meaningful: the resource
      // exists and belongs to the right company, and the write is refused all the same.
      cy.apiRequest<{ content: Array<{ id: number }> }>({
        url: '/vulnerabilities?size=1',
        token: session.accessToken,
      }).then((response) => {
        expect(response.body.content, 'o seed demo precisa ter ao menos um achado').to.have.length
          .greaterThan(0);
        vulnerabilityId = response.body.content[0].id;
      });
    });
  });

  // --- the screen -----------------------------------------------------------

  it('não mostra as afordâncias de escrita', () => {
    cy.loginAs('viewer@demo.test', '/dashboard');
    cy.byTestId('summary-cards').should('exist');
    cy.byTestId('report-export').should('not.exist');

    cy.visit('/vulnerabilities');
    cy.byTestId('vulnerability-search').should('exist');
    cy.byTestId('vulnerability-create').should('not.exist');
    cy.byTestId('vulnerability-export').should('not.exist');

    cy.visit('/projects');
    cy.byTestId('project-create').should('not.exist');

    cy.visit('/assets');
    cy.byTestId('asset-create').should('not.exist');
  });

  it('cai em /403 na navegação direta para as rotas restritas', () => {
    cy.loginAs('viewer@demo.test', '/dashboard');

    ['/vulnerabilities/nova', '/projects/novo', '/assets/novo', '/users', '/audit'].forEach(
      (route) => {
        cy.visit(route);
        cy.location('pathname').should('eq', '/403');
        cy.get('.error-card__code').should('contain.text', '403');
      },
    );
  });

  // --- the API, which is where the real control lives -----------------------

  it('recebe 403 do backend em toda rota de escrita ou de exportação', () => {
    cy.apiRequest({
      method: 'POST',
      url: '/vulnerabilities',
      token: viewer.accessToken,
      failOnStatusCode: false,
      body: {
        assetId: 1,
        title: 'Escrita que o VIEWER não deveria conseguir',
        severity: 'HIGH',
      },
    })
      .its('status')
      .should('eq', 403);

    cy.apiRequest({
      url: '/vulnerabilities/export',
      token: viewer.accessToken,
      failOnStatusCode: false,
    })
      .its('status')
      .should('eq', 403);

    cy.apiRequest({
      url: '/reports/executive',
      token: viewer.accessToken,
      failOnStatusCode: false,
    })
      .its('status')
      .should('eq', 403);
  });

  it('recebe 403 ao tentar anexar um arquivo', () => {
    // Multipart assembled by hand because the controller's `@RequestPart("file")` resolves the
    // argument before the service's `@PreAuthorize` runs: a body without the `file` part would
    // return 400 and the test would conclude, wrongly, that the role rule worked.
    const boundary = '----securityhubCypressBoundary';
    const body = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="file"; filename="evidencia.pdf"',
      'Content-Type: application/pdf',
      '',
      '%PDF-1.7\nenviado por um VIEWER\n%%EOF',
      `--${boundary}--`,
      '',
    ].join('\r\n');

    cy.apiRequest({
      method: 'POST',
      url: `/vulnerabilities/${vulnerabilityId}/attachments`,
      token: viewer.accessToken,
      failOnStatusCode: false,
      headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
      body,
    })
      .its('status')
      .should('eq', 403);
  });

  it('continua podendo ler, que é exatamente o papel dele', () => {
    cy.apiRequest({ url: '/vulnerabilities?size=1', token: viewer.accessToken })
      .its('status')
      .should('eq', 200);
    cy.apiRequest({ url: '/dashboard/summary', token: viewer.accessToken })
      .its('status')
      .should('eq', 200);
  });
});
