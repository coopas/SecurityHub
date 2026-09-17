import { AuthSession } from '../support/commands';

/**
 * Isolation between companies, with one detail that is the entire subject of this file: the
 * answer has to be **404, never 403**.
 *
 * A 403 is a confirmation. It says "this id exists, and it is not yours" — and whoever is
 * probing learns exactly what they wanted: the range of ids in use, how many findings the
 * competitor has, when they registered the next one. The 404 says nothing: for the company
 * asking, the resource simply does not exist, which is the truth from its point of view.
 *
 * The `demo` profile seed creates two tenants precisely for this: `demo`, with the full set, and
 * `northwind`, with one administrator. The northwind administrator is the most powerful role
 * there is in the product — and still does not see a single byte of demo's.
 */
describe('Isolamento entre empresas', () => {
  let northwind: AuthSession;
  let demoVulnerabilityId: number;
  let demoAttachmentId: number | null = null;

  before(() => {
    // A real target from the demo company, found with demo's own session.
    cy.loginAs('admin@demo.test').then((demoAdmin) => {
      cy.apiRequest<{ content: Array<{ id: number }> }>({
        url: '/vulnerabilities?size=1',
        token: demoAdmin.accessToken,
      }).then((listing) => {
        expect(listing.body.content, 'o seed demo precisa ter ao menos um achado').to.have.length
          .greaterThan(0);
        demoVulnerabilityId = listing.body.content[0].id;

        cy.apiRequest<Array<{ id: number }>>({
          url: `/vulnerabilities/${demoVulnerabilityId}/attachments`,
          token: demoAdmin.accessToken,
        }).then((attachments) => {
          demoAttachmentId = attachments.body.length > 0 ? attachments.body[0].id : null;
        });
      });
    });

    cy.loginAs('admin@northwind.test').then((session) => {
      northwind = session;
    });
  });

  it('o administrador da northwind recebe 404 — e não 403 — em todo recurso da demo', () => {
    const attempts: Array<{ method: string; url: string; what: string }> = [
      {
        method: 'GET',
        url: `/vulnerabilities/${demoVulnerabilityId}`,
        what: 'leitura do achado',
      },
      {
        method: 'DELETE',
        url: `/vulnerabilities/${demoVulnerabilityId}`,
        what: 'exclusão do achado',
      },
      {
        method: 'GET',
        url: `/vulnerabilities/${demoVulnerabilityId}/attachments`,
        what: 'listagem de anexos',
      },
      {
        // The attachment id may not exist; it makes no difference, and that is the point. The
        // parent is proved first, so an attachment reached through the wrong vulnerability — or
        // by another company — is the same 404 as one that never existed.
        method: 'GET',
        url: `/vulnerabilities/${demoVulnerabilityId}/attachments/${demoAttachmentId ?? 1}/download`,
        what: 'download de anexo',
      },
    ];

    attempts.forEach(({ method, url, what }) => {
      cy.apiRequest({
        method,
        url,
        token: northwind.accessToken,
        failOnStatusCode: false,
      }).then((response) => {
        expect(response.status, `${what} (${method} ${url})`).to.eq(404);
        // Explicit because it is the defect this file exists to catch: a 403 here would
        // confirm the id's existence to someone who should not even know that much.
        expect(response.status, `${what} não pode responder 403`).to.not.eq(403);
      });
    });
  });

  it('as listagens da northwind não contêm nada da demo', () => {
    cy.apiRequest<{ content: Array<{ id: number }>; totalElements: number }>({
      url: '/vulnerabilities?size=100',
      token: northwind.accessToken,
    }).then((response) => {
      const ids = response.body.content.map((row) => row.id);
      expect(ids).to.not.include(demoVulnerabilityId);
    });
  });

  it('o recurso continua existindo para a empresa dona', () => {
    cy.loginAs('admin@demo.test').then((demoAdmin) => {
      cy.apiRequest({
        url: `/vulnerabilities/${demoVulnerabilityId}`,
        token: demoAdmin.accessToken,
      })
        .its('status')
        .should('eq', 200);
    });
  });
});
