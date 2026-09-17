/**
 * Suíte end-to-end do SecurityHub.
 *
 * ============================================================================
 * COMO ESCREVER UM TESTE AQUI SEM CRIAR UM FALSO VERMELHO
 * ============================================================================
 *
 * O alvo é a pilha do `docker compose` com o seed do perfil `demo`, e esse seed é datado de
 * forma **relativa**: as vulnerabilidades nascem com `discoveredAt` e `dueDate` calculados a
 * partir de `Instant.now()` no momento em que o contêiner subiu. Isso significa que o conjunto
 * de dados muda sozinho conforme o relógio anda — uma vulnerabilidade que hoje está dentro do
 * prazo vence amanhã, e o número do cartão "Atrasadas" do dashboard é diferente a cada dia.
 * Um teste que fixe esses números quebra sem que nada no produto tenha mudado, e um teste que
 * quebra sozinho é um teste que alguém vai acabar desligando.
 *
 * As cinco regras abaixo são o que mantém esta suíte determinística. Elas valem para todo
 * arquivo em `cypress/e2e`.
 *
 * 1. **Nunca afirme um número absoluto que dependa do relógio.** Afirme uma *relação* entre
 *    dois valores que o produto calcula por caminhos diferentes. O exemplo canônico é o
 *    cartão "Atrasadas" do dashboard contra o `totalElements` de
 *    `GET /vulnerabilities?overdue=true`: são a agregação do banco e a listagem paginada,
 *    e uma divergência entre elas é o defeito mais visível que essa tela pode ter. A relação
 *    é verdadeira em qualquer dia; o número, não.
 *
 * 2. **Nunca afirme uma data formatada.** "17/09/2026" depende do relógio, do fuso do
 *    contêiner e do locale do navegador. Se a data importa, afirme que o campo existe e não
 *    está vazio, ou compare com o valor que a própria API devolveu.
 *
 * 3. **Crie o dado sobre o qual você afirma.** Toda linha criada por um teste leva um título
 *    único — o padrão é `Cypress ${Date.now()}` — e é apagada no `after()` do próprio arquivo.
 *    Assim dois testes nunca disputam a mesma linha, uma execução não deixa resíduo para a
 *    seguinte, e a afirmação não depende de o seed ter exatamente o conteúdo de hoje.
 *
 * 4. **Autentique pela API, exceto no teste do formulário de login.** `cy.loginAs()` faz o
 *    `POST /auth/login` e semeia o `localStorage` antes de a aplicação inicializar. Passar pelo
 *    formulário em todo teste acrescentaria a cada um deles a chance de falhar por um motivo
 *    que `01-login.cy.ts` já cobre — e que só ele deveria cobrir.
 *
 * 5. **Permissão se testa na API, não no CSS.** Esconder um botão não é um controle: é
 *    conveniência. Um teste que só verifica a ausência do botão estaria testando a camada
 *    errada, e passaria intacto com o backend completamente aberto. Toda afirmação de
 *    permissão nesta suíte tem um par: a ausência da afordância **e** o 403 da rota
 *    correspondente, chamada direto com o token do papel.
 */

import './commands';

/**
 * O `ResizeObserver loop limit exceeded` vem do Angular Material (sidenav e mat-table
 * remedindo no mesmo quadro). É ruído do navegador, não uma exceção da aplicação: nenhum
 * código nosso está no stack e nada quebra. Qualquer outra exceção não tratada continua
 * derrubando o teste, que é exatamente o que se quer de um erro de verdade.
 */
Cypress.on('uncaught:exception', (error) => {
  if (/ResizeObserver loop/i.test(error.message)) {
    return false;
  }
  return undefined;
});
