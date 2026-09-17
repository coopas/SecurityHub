import { AuthSession } from '../support/commands';

/**
 * Permissão do papel VIEWER, verificada nas duas camadas em que ela existe.
 *
 * A metade de cima olha a tela: nenhuma afordância de escrita, e `/403` na navegação direta.
 * A metade de baixo chama a API com o token do próprio VIEWER e exige 403.
 *
 * As duas são necessárias, e é a de baixo que importa. Esconder um botão não é um controle —
 * é conveniência; quem quiser escrever não precisa do botão, precisa de um `curl`. Uma suíte
 * que só verificasse o CSS estaria testando a camada errada e passaria intacta com o backend
 * completamente aberto. É o próprio princípio que este projeto aplica no backend, e vale aqui
 * também.
 */
describe('VIEWER não escreve, nem pela tela nem pela API', () => {
  let viewer: AuthSession;
  let vulnerabilityId: number;

  before(() => {
    cy.loginAs('viewer@demo.test').then((session) => {
      viewer = session;
      // Ler é permitido a todo papel, então a própria sessão do VIEWER serve para achar um
      // alvo existente da empresa dele — que é o que torna um 403 significativo: o recurso
      // existe e é da empresa certa, e ainda assim a escrita é recusada.
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

  // --- a tela ---------------------------------------------------------------

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

  // --- a API, que é onde o controle de verdade está -------------------------

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
    // Multipart montado à mão porque o `@RequestPart("file")` do controller resolve o
    // argumento antes de o `@PreAuthorize` do serviço rodar: um corpo sem a parte `file`
    // devolveria 400 e o teste concluiria, erradamente, que a regra de papel funcionou.
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
