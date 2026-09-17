import { AuthSession, demoPassword } from '../support/commands';

/**
 * Import of a scan report, through the screen, the way an analyst does it: send the file,
 * review what the server found, give an asset to the finding that ended up without one and
 * confirm — and then watch the row show up in the history as confirmed.
 *
 * <b>A single `it` for the flow</b>, for the same reason as `02`: each step depends on the id
 * the previous step produced, and separate `it`s would start with a clean `localStorage`.
 * With `retries: 2`, repeating only the step that failed would re-run the `POST` over an
 * already assembled state — and a second send of the same file is not harmless here: the
 * fingerprints would already be in the backlog and every finding would come back as
 * "já registrado". The whole `it` repeats with a fresh token and stays reproducible.
 *
 * <b>The run token.</b> V9's deduplication is a partial unique index on
 * `(company_id, fingerprint)`, and the fingerprint is `sha256(scanner:ruleId:target:cve)`.
 * A fixed file imported twice into the same company is, by contract, zero new findings.
 * So the `id` of every script in the report gets `Cypress-${Date.now()}` before the send: it is
 * the same pattern as rule 3 of `support/e2e.ts` — the test creates the data it asserts on —
 * applied to the only field the fingerprint sees.
 *
 * <b>Against the `demo` seed, inventing nothing.</b> The report's first two targets are
 * `api.pagamentos.demo.test` and `10.20.0.11` — the identifiers of the assets "API de
 * Pagamentos" and "Gateway de Borda" from the project "Plataforma de Pagamentos" —, which is why
 * they find their asset on their own. The third is `host-desconhecido.demo.test`, which the seed
 * does not have: it is the finding with no asset that the test links by hand. The matching is
 * per project, so the send has to be to that project and not to another.
 *
 * <b>What is left behind.</b> The `after()` tries to delete the vulnerabilities it created, and
 * today it cannot: `scan_findings.vulnerability_id` references the row, and
 * `VulnerabilityService.delete` only handles comments and attachments, so the deletion answers
 * 409. The cleanup is done with `failOnStatusCode: false` on purpose — it starts working again
 * on its own the day the deletion handles the reference, and until then it does not turn a known
 * product defect into red in this suite. The `scan_imports` row stays either way: the API has no
 * deletion — `DELETE /scan-imports/{id}` is the discard, and it only applies to a pending import
 * — and a confirmed import is a historical fact.
 *
 * The residue does not make the suite unstable: the run token leaves each round with findings of
 * its own, and no assertion here — nor, as far as this repository goes, in any other file —
 * depends on an absolute count of the `demo` tenant.
 */
describe('Importação de um relatório de varredura', () => {
  // No space, unlike the `Cypress ${Date.now()}` of the other files: this token goes into the
  // file name and into the `id` of an nmap script, and both are read back as running text.
  const run = `Cypress-${Date.now()}`;
  /** Unique per run, and it is what the history row is found by. */
  const filename = `nmap-${run}.xml`;
  const project = 'Plataforma de Pagamentos';
  /** The two assets the report's targets resolve on their own. */
  const matchedAsset = 'API de Pagamentos';
  const secondMatchedAsset = 'Gateway de Borda';
  /** The asset picked by hand for the finding that ended up without one. */
  const mappedAsset = 'Banco de Transações';

  interface Finding {
    id: number;
    ruleId: string;
    title: string;
    target: string;
    status: string;
    assetId: number | null;
    assetName: string | null;
    vulnerabilityId: number | null;
  }

  interface ScanImport {
    id: number;
    status: string;
    projectName: string;
    originalFilename: string;
    totalFindings: number;
    matchedCount: number;
    unmatchedCount: number;
    duplicateCount: number;
    importedCount: number;
    skippedCount: number;
    findings: Finding[];
  }

  after(() => {
    // ADMIN, and not the analyst who imported: deleting a vulnerability is an administrator's
    // privilege. The search is by the run token, which is in the title of every imported finding
    // (in nmap the title is the script id), and it deletes everything it finds — a retry may
    // have left more than one row.
    //
    // Best-effort: see this file's header. As long as the deletion does not handle
    // `scan_findings.vulnerability_id`, every DELETE here answers 409 and the row stays.
    cy.request({
      method: 'POST',
      url: '/api/v1/auth/login',
      body: { email: 'admin@demo.test', password: demoPassword() },
    }).then((login) => {
      const token = (login.body as AuthSession).accessToken;
      cy.apiRequest<{ content: Array<{ id: number; title: string }> }>({
        url: `/vulnerabilities?search=${encodeURIComponent(run)}&size=50`,
        token,
      }).then((listing) => {
        listing.body.content
          .filter((found) => found.title.includes(run))
          .forEach((found) => {
            cy.apiRequest({
              method: 'DELETE',
              url: `/vulnerabilities/${found.id}`,
              token,
              failOnStatusCode: false,
            });
          });
      });
    });
  });

  it('envia o relatório, mapeia o achado sem ativo, confirma e aparece no histórico', () => {
    cy.fixture('nmap-scan.xml').then((template: string) => {
      const report = template.split('__RUN__').join(run);

      cy.loginAs('analyst@demo.test', '/imports/novo').then((analyst) => {
        // --- send -----------------------------------------------------------
        cy.byTestId('import-project').click();
        cy.contains('mat-option', project).click();

        cy.byTestId('import-format').click();
        cy.contains('mat-option', 'Nmap (XML)').click();

        // The `input[type=file]` is `hidden` — what triggers it is the Material button — so
        // the `force` here is the same as in `02` with the attachment. The content goes
        // assembled in memory, and not by the file path, precisely because it is not the
        // repository's file: it is the repository's file with the run token inside.
        cy.byTestId('import-file-input').selectFile(
          {
            contents: Cypress.Buffer.from(report),
            fileName: filename,
            mimeType: 'text/xml',
          },
          { force: true },
        );
        cy.byTestId('import-file-name').should('contain.text', filename);

        cy.byTestId('import-submit').click();

        // The form navigates to the preview of what it has just sent: the URL is the proof
        // that the server returned an id, and it is where the rest of the flow's id comes from.
        cy.location('pathname')
          .should('match', /^\/imports\/\d+$/)
          .then((pathname) => {
            const importId = Number(pathname.split('/').pop());
            expect(importId).to.be.greaterThan(0);

            // --- the preview --------------------------------------------------
            cy.byTestId('import-status').should('contain.text', 'Aguardando revisão');
            cy.byTestId('import-actions').should('exist');

            // Three open ports in the report and none of them becomes a finding: the nmap
            // importer only reads NSE script results. If that changes, this count is the first
            // thing to break.
            counter('Achados').should('have.text', '3');
            counter('Com ativo').should('have.text', '2');
            counter('Sem ativo').should('have.text', '1');
            counter('Já registrados').should('have.text', '0');

            cy.apiRequest<ScanImport>({
              url: `/scan-imports/${importId}`,
              token: analyst.accessToken,
            }).then((staged) => {
              // Nothing was created by the send: the import is a proposal until confirmation.
              expect(staged.body.status, 'a importação nasce pendente').to.eq('PENDING');
              expect(staged.body.originalFilename).to.eq(filename);
              expect(staged.body.projectName).to.eq(project);
              expect(staged.body.findings).to.have.length(3);

              const matched = findingBy(staged.body, 'ssl-heartbleed');
              const alsoMatched = findingBy(staged.body, 'smb-vuln-ms17-010');
              const unmatched = findingBy(staged.body, 'ssl-poodle');

              // The matching is by identifier within the chosen project, and it is the server
              // that does it: nobody picked this asset.
              expect(matched.status).to.eq('MATCHED');
              expect(matched.target).to.eq('api.pagamentos.demo.test');
              expect(matched.assetName).to.eq(matchedAsset);

              // The second matches by address, because that is how the asset is registered: the
              // target of an nmap finding is the hostname when there is one, and the address
              // when there is not.
              expect(alsoMatched.status).to.eq('MATCHED');
              expect(alsoMatched.target).to.eq('10.20.0.11');
              expect(alsoMatched.assetName).to.eq(secondMatchedAsset);

              // The importer never creates an asset: a target that does not exist in the
              // inventory waits for a person to say what it is.
              expect(unmatched.status).to.eq('UNMATCHED');
              // Absent, and not `null`: `default-property-inclusion: non_null` drops the key
              // from the payload, so the assertion tolerates both forms of "has no asset"
              // instead of depending on which one arrived.
              expect(unmatched.assetId == null, 'o achado sem correspondência não tem ativo').to.be
                .true;

              cy.byTestId(`import-finding-status-${matched.id}`).should(
                'contain.text',
                'Ativo identificado',
              );
              cy.byTestId(`import-finding-status-${unmatched.id}`).should(
                'contain.text',
                'Sem ativo',
              );

              // --- map the finding with no asset ------------------------------
              cy.byTestId(`import-finding-asset-${unmatched.id}`).click();
              cy.contains('mat-option', mappedAsset).click();

              cy.byTestId(`import-finding-status-${unmatched.id}`).should(
                'contain.text',
                'Ativo identificado',
              );

              cy.apiRequest<ScanImport>({
                url: `/scan-imports/${importId}`,
                token: analyst.accessToken,
              }).then((mapped) => {
                expect(mapped.body.matchedCount, 'os três achados agora têm ativo').to.eq(3);
                expect(mapped.body.unmatchedCount).to.eq(0);
                expect(mapped.body.status, 'vincular não resolve a importação').to.eq('PENDING');
                expect(findingBy(mapped.body, 'ssl-poodle').assetName).to.eq(mappedAsset);
              });

              // --- confirm -----------------------------------------------------
              cy.byTestId('import-confirm').click();
              // The shared dialog does not declare a `data-testid` and it is not this test's
              // job to change that; scoping to the Material container is what avoids hitting
              // the page's own "Confirmar importação" button.
              cy.get('mat-dialog-container').contains('button', 'Confirmar').click();

              cy.byTestId('import-status').should('contain.text', 'Confirmada');
              cy.byTestId('import-actions').should('not.exist');
              cy.byTestId('import-readonly-hint').should('exist');
              counter('Importados').should('have.text', '3');
              counter('Ignorados').should('have.text', '0');

              cy.apiRequest<ScanImport>({
                url: `/scan-imports/${importId}`,
                token: analyst.accessToken,
              }).then((confirmed) => {
                expect(confirmed.body.status).to.eq('CONFIRMED');
                expect(confirmed.body.importedCount).to.eq(3);
                expect(confirmed.body.skippedCount).to.eq(0);

                // Per-finding traceability: each row says which vulnerability it turned into.
                // The audit trail carries a single row for the whole import, and it is here
                // that the detail lives.
                confirmed.body.findings.forEach((finding) => {
                  expect(finding.status, `achado ${finding.ruleId}`).to.eq('IMPORTED');
                  expect(finding.vulnerabilityId, `achado ${finding.ruleId}`).to.be.a('number');
                });

                const created = findingBy(confirmed.body, 'ssl-heartbleed');
                cy.apiRequest<{ title: string; severity: string; cve: string; assetId: number }>({
                  url: `/vulnerabilities/${created.vulnerabilityId}`,
                  token: analyst.accessToken,
                }).then((vulnerability) => {
                  expect(vulnerability.body.title).to.eq(created.title);
                  // The CVE came out of the script's text, and nmap's severity is derived: a
                  // result that cites a CVE is high.
                  expect(vulnerability.body.cve).to.eq('CVE-2014-0160');
                  expect(vulnerability.body.severity).to.eq('HIGH');
                  expect(vulnerability.body.assetId).to.eq(created.assetId);
                });
              });

              // --- the history --------------------------------------------------
              cy.visit('/imports');
              cy.contains('tr', filename)
                .should('contain.text', project)
                .and('contain.text', 'Confirmada')
                .and('contain.text', '3 importados');
            });
          });
      });
    });
  });

  it('um VIEWER acompanha a revisão, mas não envia relatório', () => {
    cy.loginAs('viewer@demo.test', '/imports').then((viewer) => {
      // The screen: no send affordance.
      cy.byTestId('import-new').should('not.exist');
      cy.visit('/imports/novo');
      cy.location('pathname').should('eq', '/403');

      // And the layer that matters (rule 5 of `support/e2e.ts`): the API, called directly with
      // the VIEWER's own token. Reading is still allowed — that is their role.
      cy.apiRequest({ url: '/scan-imports?size=1', token: viewer.accessToken })
        .its('status')
        .should('eq', 200);

      cy.apiRequest<{ content: Array<{ id: number }> }>({
        url: '/projects?size=1',
        token: viewer.accessToken,
      }).then((projects) => {
        expect(projects.body.content, 'o seed demo precisa ter ao menos um projeto').to.have.length
          .greaterThan(0);

        // Multipart assembled by hand, as in `03`, and with the three parts filled in on
        // purpose: `projectId` and `format` are resolved by Spring's binder **before** the
        // service's `@PreAuthorize` runs, so an incomplete body would return 400 and the test
        // would conclude, wrongly, that the role rule worked.
        const boundary = '----securityhubCypressScanBoundary';
        const body = [
          `--${boundary}`,
          'Content-Disposition: form-data; name="projectId"',
          '',
          String(projects.body.content[0].id),
          `--${boundary}`,
          'Content-Disposition: form-data; name="format"',
          '',
          'NMAP_XML',
          `--${boundary}`,
          'Content-Disposition: form-data; name="file"; filename="nmap.xml"',
          'Content-Type: text/xml',
          '',
          '<?xml version="1.0"?><nmaprun></nmaprun>',
          `--${boundary}--`,
          '',
        ].join('\r\n');

        cy.apiRequest({
          method: 'POST',
          url: '/scan-imports',
          token: viewer.accessToken,
          failOnStatusCode: false,
          headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
          body,
        })
          .its('status')
          .should('eq', 403);
      });
    });
  });

  /** The `<dd>` of the counter whose `<dt>` has this label. */
  function counter(label: string): Cypress.Chainable<JQuery<HTMLElement>> {
    return cy
      .byTestId('import-counters')
      .contains('.imports-counters__item', label)
      .find('dd');
  }

  function findingBy(scanImport: ScanImport, rulePrefix: string): Finding {
    const found = scanImport.findings.find((finding) => finding.ruleId.startsWith(rulePrefix));
    expect(found, `o relatório precisa trazer o achado ${rulePrefix}`).to.not.be.undefined;
    return found as Finding;
  }
});
