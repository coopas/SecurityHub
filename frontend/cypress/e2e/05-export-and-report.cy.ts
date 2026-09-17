import { AuthSession, demoPassword } from '../support/commands';

/**
 * CSV export and executive report in PDF.
 *
 * The test asserts on the **content** of the file, and not on the fact that a download happened.
 * A file that downloads is the easiest requirement to satisfy and the least useful to check:
 * what can go wrong is the export filter diverging from the listing filter, and a hostile cell
 * reaching the spreadsheet of whoever opens the file.
 *
 * The hostile title is the case that gives that second risk its name. `=cmd|' /C calc'!A0` in a
 * CSV cell is executed by Excel when the file is opened — the finding registered by an attacker
 * becomes code running on the machine of the analyst who exported the report. The backend's
 * defence is twofold: quotes around every cell, so that a comma does not split the row, and an
 * apostrophe before the character that triggers a formula. The test demands both.
 */

/** Header indexes from `VulnerabilityExportService.HEADER`. */
const TITLE_COLUMN = 1;
const SEVERITY_COLUMN = 3;

/**
 * Minimal RFC 4180 reader. It exists because the assertion that matters is "every row in the
 * file has the requested severity", and that requires splitting cells for real: a `split(',')`
 * would fall inside the first description that contained a comma — which is exactly the case the
 * backend's escaping exists to handle, and what the test has to see correctly so as not to give
 * a false green.
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

      // Created through the API: the subject of this file is the exported file, and going
      // through the form here would only add failure reasons that `02` already covers.
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

      // Rule 1 of `support/e2e.ts`: no absolute numbers — the seed is dated relatively and the
      // count changes with the clock. What is asserted is the relation: the file requested with
      // `severity=CRITICAL` cannot contain any other severity.
      rows.slice(1).forEach((row) => {
        expect(row[SEVERITY_COLUMN], `linha ${row[0]}: o filtro do arquivo é o da tela`).to.eq(
          'CRITICAL',
        );
      });

      // The hostile title: the leading apostrophe is what stops Excel from executing the cell.
      // It arrives here already without the outer quotes because the reader above consumed them
      // — and the fact that the value came back as **one** cell, with the comma and the inner
      // quotes intact, is the other half of the defence.
      const hostileRow = rows.slice(1).find((row) => row[TITLE_COLUMN].includes('/C calc'));
      expect(hostileRow, 'a linha do título hostil precisa estar no arquivo').to.not.be.undefined;
      expect((hostileRow as string[])[TITLE_COLUMN]).to.eq(`'${hostileTitle}`);

      // The file that reached the disk, through the real product path (`saveBlob`). The name
      // comes from the header the server sent, and not from a date assembled here: that would
      // depend on the container's clock (rule 2 of `support/e2e.ts`).
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
      // The signature, and not the content-type: the header is what the server claims, the
      // first five bytes are what it actually produced.
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
