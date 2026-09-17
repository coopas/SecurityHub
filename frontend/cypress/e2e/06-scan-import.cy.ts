import { AuthSession, demoPassword } from '../support/commands';

/**
 * Importação de um relatório de varredura, pela tela, como um analista faz: enviar o
 * arquivo, revisar o que o servidor encontrou, dar um ativo ao achado que ficou sem um e
 * confirmar — e então ver a linha aparecer no histórico como confirmada.
 *
 * <b>Um único `it` para o fluxo</b>, pela mesma razão de `02`: cada passo depende do id
 * que o passo anterior produziu, e `it`s separados começariam com `localStorage` limpo.
 * Com `retries: 2`, repetir só o passo que falhou reexecutaria o `POST` sobre um estado já
 * montado — e um segundo envio do mesmo arquivo não é inócuo aqui: as impressões digitais
 * já estariam no backlog e todo achado voltaria como "já registrado". O `it` inteiro
 * repete com um token novo e continua reprodutível.
 *
 * <b>O token de execução.</b> A deduplicação da V9 é um índice único parcial em
 * `(company_id, fingerprint)`, e a impressão digital é `sha256(scanner:ruleId:target:cve)`.
 * Um arquivo fixo importado duas vezes na mesma empresa é, por contrato, zero achado novo.
 * Então o `id` de cada script do relatório recebe `Cypress-${Date.now()}` antes do envio: é
 * o mesmo padrão da regra 3 de `support/e2e.ts` — o teste cria o dado sobre o qual afirma —
 * aplicado ao único campo que a impressão digital enxerga.
 *
 * <b>Contra o seed `demo`, sem inventar nada.</b> Os dois primeiros alvos do relatório são
 * `api.pagamentos.demo.test` e `10.20.0.11` — os identificadores dos ativos "API de
 * Pagamentos" e "Gateway de Borda" do projeto "Plataforma de Pagamentos" —, por isso eles
 * encontram o ativo sozinhos. O terceiro é `host-desconhecido.demo.test`, que o seed não tem:
 * é o achado sem ativo que o teste vincula à mão. O casamento é por projeto, então o envio
 * precisa ser para esse projeto e não para outro.
 *
 * <b>O que fica para trás.</b> O `after()` tenta apagar as vulnerabilidades criadas, e hoje
 * não consegue: `scan_findings.vulnerability_id` referencia a linha, e
 * `VulnerabilityService.delete` só trata comentários e anexos, então a exclusão responde 409.
 * A limpeza é feita com `failOnStatusCode: false` de propósito — ela volta a funcionar sozinha
 * no dia em que a exclusão tratar a referência, e até lá não transforma um defeito conhecido do
 * produto em vermelho desta suíte. A linha de `scan_imports` fica de todo jeito: a API não tem
 * exclusão — `DELETE /scan-imports/{id}` é o descarte, e só vale para uma importação pendente —
 * e uma importação confirmada é um fato histórico.
 *
 * O resíduo não torna a suíte instável: o token de execução deixa cada rodada com achados
 * próprios, e nenhuma afirmação daqui — nem, até onde este repositório vai, de qualquer outro
 * arquivo — depende de uma contagem absoluta do tenant `demo`.
 */
describe('Importação de um relatório de varredura', () => {
  // Sem espaço, ao contrário do `Cypress ${Date.now()}` dos outros arquivos: este token
  // entra no nome do arquivo e no `id` de um script de nmap, e os dois são lidos de volta
  // como texto corrido.
  const run = `Cypress-${Date.now()}`;
  /** Único por execução, e é por ele que a linha do histórico é encontrada. */
  const filename = `nmap-${run}.xml`;
  const project = 'Plataforma de Pagamentos';
  /** Os dois ativos que os alvos do relatório resolvem sozinhos. */
  const matchedAsset = 'API de Pagamentos';
  const secondMatchedAsset = 'Gateway de Borda';
  /** O ativo escolhido à mão para o achado que ficou sem um. */
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
    // ADMIN, e não o analista que importou: excluir vulnerabilidade é privilégio de
    // administrador. A busca é pelo token da execução, que está no título de todo achado
    // importado (no nmap o título é o id do script), e apaga tudo o que encontrar — uma
    // repetição pode ter deixado mais de uma linha.
    //
    // Best-effort: veja o cabeçalho deste arquivo. Enquanto a exclusão não tratar
    // `scan_findings.vulnerability_id`, cada DELETE aqui responde 409 e a linha fica.
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
        // --- enviar ---------------------------------------------------------
        cy.byTestId('import-project').click();
        cy.contains('mat-option', project).click();

        cy.byTestId('import-format').click();
        cy.contains('mat-option', 'Nmap (XML)').click();

        // O `input[type=file]` é `hidden` — quem o aciona é o botão do Material — então o
        // `force` aqui é o mesmo de `02` com o anexo. O conteúdo vai montado em memória, e
        // não pelo caminho do arquivo, justamente porque ele não é o do repositório: é o
        // do repositório com o token da execução dentro.
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

        // O formulário navega para a prévia do que acabou de enviar: a URL é a prova de
        // que o servidor devolveu um id, e é de onde sai o id do resto do fluxo.
        cy.location('pathname')
          .should('match', /^\/imports\/\d+$/)
          .then((pathname) => {
            const importId = Number(pathname.split('/').pop());
            expect(importId).to.be.greaterThan(0);

            // --- a prévia -----------------------------------------------------
            cy.byTestId('import-status').should('contain.text', 'Aguardando revisão');
            cy.byTestId('import-actions').should('exist');

            // Três portas abertas no relatório e nenhuma delas vira achado: o importador de
            // nmap lê apenas resultado de script NSE. Se isso mudar, esta contagem é a
            // primeira coisa a quebrar.
            counter('Achados').should('have.text', '3');
            counter('Com ativo').should('have.text', '2');
            counter('Sem ativo').should('have.text', '1');
            counter('Já registrados').should('have.text', '0');

            cy.apiRequest<ScanImport>({
              url: `/scan-imports/${importId}`,
              token: analyst.accessToken,
            }).then((staged) => {
              // Nada foi criado pelo envio: a importação é uma proposta até a confirmação.
              expect(staged.body.status, 'a importação nasce pendente').to.eq('PENDING');
              expect(staged.body.originalFilename).to.eq(filename);
              expect(staged.body.projectName).to.eq(project);
              expect(staged.body.findings).to.have.length(3);

              const matched = findingBy(staged.body, 'ssl-heartbleed');
              const alsoMatched = findingBy(staged.body, 'smb-vuln-ms17-010');
              const unmatched = findingBy(staged.body, 'ssl-poodle');

              // O casamento é por identificador dentro do projeto escolhido, e é o
              // servidor que o faz: ninguém escolheu este ativo.
              expect(matched.status).to.eq('MATCHED');
              expect(matched.target).to.eq('api.pagamentos.demo.test');
              expect(matched.assetName).to.eq(matchedAsset);

              // O segundo casa por endereço, porque é assim que o ativo está cadastrado: o
              // alvo de um achado de nmap é o hostname quando existe, e o endereço quando não.
              expect(alsoMatched.status).to.eq('MATCHED');
              expect(alsoMatched.target).to.eq('10.20.0.11');
              expect(alsoMatched.assetName).to.eq(secondMatchedAsset);

              // O importador nunca cria um ativo: um alvo que não existe no inventário
              // fica esperando uma pessoa dizer o que ele é.
              expect(unmatched.status).to.eq('UNMATCHED');
              // Ausente, e não `null`: `default-property-inclusion: non_null` tira a chave
              // do payload, então a afirmação tolera as duas formas de "não tem ativo" em
              // vez de depender de qual delas chegou.
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

              // --- mapear o achado sem ativo ----------------------------------
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

              // --- confirmar ---------------------------------------------------
              cy.byTestId('import-confirm').click();
              // O diálogo compartilhado não declara `data-testid` e não é deste teste
              // mudá-lo; o escopo do container do Material é o que evita acertar o botão
              // "Confirmar importação" da própria página.
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

                // A rastreabilidade por achado: cada linha diz em qual vulnerabilidade
                // virou. A trilha de auditoria carrega uma linha só para a importação
                // inteira, e é aqui que o detalhe mora.
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
                  // O CVE saiu do texto do script, e a severidade do nmap é derivada: um
                  // resultado que cita um CVE é alto.
                  expect(vulnerability.body.cve).to.eq('CVE-2014-0160');
                  expect(vulnerability.body.severity).to.eq('HIGH');
                  expect(vulnerability.body.assetId).to.eq(created.assetId);
                });
              });

              // --- o histórico --------------------------------------------------
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
      // A tela: nenhuma afordância de envio.
      cy.byTestId('import-new').should('not.exist');
      cy.visit('/imports/novo');
      cy.location('pathname').should('eq', '/403');

      // E a camada que importa (regra 5 de `support/e2e.ts`): a API, chamada direto com o
      // token do próprio VIEWER. Ler continua liberado — é o papel dele.
      cy.apiRequest({ url: '/scan-imports?size=1', token: viewer.accessToken })
        .its('status')
        .should('eq', 200);

      cy.apiRequest<{ content: Array<{ id: number }> }>({
        url: '/projects?size=1',
        token: viewer.accessToken,
      }).then((projects) => {
        expect(projects.body.content, 'o seed demo precisa ter ao menos um projeto').to.have.length
          .greaterThan(0);

        // Multipart montado à mão, como em `03`, e com as três partes preenchidas de
        // propósito: `projectId` e `format` são resolvidos pelo binder do Spring **antes**
        // de o `@PreAuthorize` do serviço rodar, então um corpo incompleto devolveria 400 e
        // o teste concluiria, erradamente, que a regra de papel funcionou.
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

  /** O `<dd>` do contador cujo `<dt>` tem este rótulo. */
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
