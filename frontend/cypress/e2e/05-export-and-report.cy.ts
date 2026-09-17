import { AuthSession, demoPassword } from '../support/commands';

/**
 * Exportação em CSV e relatório executivo em PDF.
 *
 * O teste afirma sobre o **conteúdo** do arquivo, e não sobre o fato de um download ter
 * acontecido. Um arquivo que baixa é o requisito mais fácil de satisfazer e o menos útil de
 * verificar: o que pode dar errado é o filtro da exportação divergir do filtro da listagem, e
 * uma célula hostil chegar à planilha de quem abrir o arquivo.
 *
 * O título hostil é o caso que dá nome a esse segundo risco. `=cmd|' /C calc'!A0` numa célula
 * de CSV é executado pelo Excel ao abrir o arquivo — o achado registrado por um invasor vira
 * código rodando na máquina do analista que exportou o relatório. A defesa do backend é dupla:
 * aspas em toda célula, para que uma vírgula não parta a linha, e apóstrofo antes do caractere
 * que dispara fórmula. O teste exige as duas.
 */

/** Índices do cabeçalho de `VulnerabilityExportService.HEADER`. */
const TITLE_COLUMN = 1;
const SEVERITY_COLUMN = 3;

/**
 * Leitor RFC 4180 mínimo. Existe porque a asserção que interessa é "toda linha do arquivo tem
 * a severidade pedida", e isso exige separar células de verdade: um `split(',')` cairia dentro
 * da primeira descrição que contivesse uma vírgula — que é justamente o caso que o escape do
 * backend existe para tratar, e o que o teste precisa enxergar corretamente para não dar um
 * verde falso.
 */
function parseCsv(raw: string): string[][] {
  const text = raw.replace(/^\uFEFF/, '');
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;

  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"') {
        if (text[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else if (char !== '\r') {
      cell += char;
    }
  }
  if (cell.length > 0 || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

describe('Exportação em CSV e relatório executivo', () => {
  const hostileTitle = `=cmd|' /C calc'!A0 Cypress ${Date.now()}`;
  let analyst: AuthSession;
  let createdId: number | null = null;

  before(() => {
    cy.loginAs('analyst@demo.test', '/vulnerabilities').then((session) => {
      analyst = session;

      // Criado pela API: o assunto deste arquivo é o arquivo exportado, e passar pelo
      // formulário aqui só acrescentaria motivos de falha que `02` já cobre.
      cy.apiRequest<{ content: Array<{ id: number }> }>({
        url: '/assets?size=1',
        token: session.accessToken,
      }).then((assets) => {
        expect(assets.body.content).to.have.length.greaterThan(0);
        cy.apiRequest<{ id: number }>({
          method: 'POST',
          url: '/vulnerabilities',
          token: session.accessToken,
          body: {
            assetId: assets.body.content[0].id,
            title: hostileTitle,
            description: 'Título hostil criado pela suíte end-to-end.',
            severity: 'CRITICAL',
          },
        }).then((created) => {
          createdId = created.body.id;
        });
      });
    });
  });

  after(() => {
    if (createdId === null) {
      return;
    }
    cy.request({
      method: 'POST',
      url: '/api/v1/auth/login',
      body: { email: 'admin@demo.test', password: demoPassword() },
    }).then((login) => {
      cy.apiRequest({
        method: 'DELETE',
        url: `/vulnerabilities/${createdId}`,
        token: (login.body as AuthSession).accessToken,
        failOnStatusCode: false,
      });
    });
  });

  it('exporta o que está na tela, com toda célula escapada', () => {
    cy.intercept('GET', '/api/v1/vulnerabilities/export*').as('export');

    cy.loginAs('analyst@demo.test', '/vulnerabilities?severity=CRITICAL');
    cy.byTestId('vulnerability-export').should('be.enabled').click();

    cy.wait('@export').then((interception) => {
      expect(interception.response?.statusCode).to.eq(200);
      const rows = parseCsv(String(interception.response?.body ?? ''));

      expect(rows.length, 'o arquivo precisa ter cabeçalho e ao menos uma linha').to.be.greaterThan(
        1,
      );
      expect(rows[0][TITLE_COLUMN]).to.eq('título');
      expect(rows[0][SEVERITY_COLUMN]).to.eq('severidade');

      // Regra 1 de `support/e2e.ts`: nada de número absoluto — o seed é datado de forma
      // relativa e a contagem muda com o relógio. O que se afirma é a relação: o arquivo
      // pedido com `severity=CRITICAL` não pode conter nenhuma outra severidade.
      rows.slice(1).forEach((row) => {
        expect(row[SEVERITY_COLUMN], `linha ${row[0]}: o filtro do arquivo é o da tela`).to.eq(
          'CRITICAL',
        );
      });

      // O título hostil: o apóstrofo à frente é o que impede o Excel de executar a célula.
      // Chega aqui já sem as aspas externas porque o leitor acima as consumiu — e o fato de
      // o valor ter voltado como **uma** célula, com a vírgula e as aspas internas intactas,
      // é a outra metade da defesa.
      const hostileRow = rows.slice(1).find((row) => row[TITLE_COLUMN].includes('/C calc'));
      expect(hostileRow, 'a linha do título hostil precisa estar no arquivo').to.not.be.undefined;
      expect((hostileRow as string[])[TITLE_COLUMN]).to.eq(`'${hostileTitle}`);

      // O arquivo que chegou ao disco, pelo caminho real do produto (`saveBlob`). O nome vem
      // do cabeçalho que o servidor mandou, e não de uma data montada aqui: ela dependeria do
      // relógio do contêiner (regra 2 de `support/e2e.ts`).
      const disposition = interception.response?.headers['content-disposition'];
      const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(String(disposition ?? ''));
      expect(match, `nome do arquivo em ${String(disposition)}`).to.not.be.null;
      const filename = decodeURIComponent((match as RegExpExecArray)[1]);

      cy.readFile(`cypress/downloads/${filename}`, { timeout: 20000 }).should(
        'contain',
        '"severidade"',
      );
    });
  });

  it('o relatório executivo é um PDF de verdade', () => {
    cy.apiRequest<string>({
      url: '/reports/executive',
      token: analyst.accessToken,
      encoding: 'binary',
    }).then((response) => {
      expect(response.status).to.eq(200);
      expect(response.headers['content-type']).to.contain('application/pdf');
      // A assinatura, e não o content-type: o cabeçalho é o que o servidor afirma, os cinco
      // primeiros bytes são o que ele realmente produziu.
      expect(response.body.slice(0, 5)).to.eq('%PDF-');
      expect(response.body.length, 'um PDF de uma página já passa de 1 kB').to.be.greaterThan(1000);
    });
  });

  it('um VIEWER não exporta nem gera o relatório', () => {
    cy.loginAs('viewer@demo.test').then((viewer) => {
      cy.apiRequest({
        url: '/reports/executive',
        token: viewer.accessToken,
        failOnStatusCode: false,
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
    });
  });
});
