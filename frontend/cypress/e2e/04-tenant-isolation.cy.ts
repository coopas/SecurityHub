import { AuthSession } from '../support/commands';

/**
 * Isolamento entre empresas, com um detalhe que é o assunto inteiro deste arquivo: a resposta
 * tem de ser **404, nunca 403**.
 *
 * Um 403 é uma confirmação. Diz "este id existe, e não é seu" — e quem está sondando aprende
 * exatamente o que queria: a faixa de ids em uso, quantos achados o concorrente tem, quando ele
 * cadastrou o próximo. O 404 não diz nada: para a empresa que pergunta, o recurso simplesmente
 * não existe, que é a verdade do ponto de vista dela.
 *
 * O seed do perfil `demo` cria dois tenants justamente para isto: `demo`, com o conjunto
 * completo, e `northwind`, com um administrador. O administrador da northwind é o papel mais
 * poderoso que existe no produto — e ainda assim não enxerga um único byte da demo.
 */
describe('Isolamento entre empresas', () => {
  let northwind: AuthSession;
  let demoVulnerabilityId: number;
  let demoAttachmentId: number | null = null;

  before(() => {
    // Um alvo real da empresa demo, encontrado com a sessão da própria demo.
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
        // O id do anexo pode não existir; é indiferente, e esse é o ponto. O pai é provado
        // primeiro, então um anexo alcançado pela vulnerabilidade errada — ou por outra
        // empresa — é o mesmo 404 de um que nunca existiu.
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
        // Explícito porque é o defeito que este arquivo existe para pegar: um 403 aqui
        // confirmaria a existência do id para quem não deveria saber nem isso.
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
