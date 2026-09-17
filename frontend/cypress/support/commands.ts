/// <reference types="cypress" />

/**
 * Três comandos, e só três. Cada um existe porque a alternativa seria repetir a mesma
 * decisão em cinco arquivos e deixá-la divergir em um deles.
 */

/** Espelha `AuthResponse` do backend. Só os campos que a suíte realmente usa. */
export interface AuthSession {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: {
    id: number;
    name: string;
    email: string;
    role: 'ADMIN' | 'ANALYST' | 'DEVELOPER' | 'VIEWER';
    active: boolean;
    companyId: number;
    companyName: string;
    lastLoginAt: string | null;
    createdAt: string;
  };
}

export interface ApiRequestOptions extends Partial<Cypress.RequestOptions> {
  /** Bearer token. Omitido, a chamada vai sem `Authorization` — que é como se testa o 401. */
  token?: string;
  /** Caminho a partir de `/api/v1`, com a barra inicial: `/vulnerabilities/7`. */
  url: string;
}

/**
 * As três chaves que `AuthService` lê no construtor, em `restoreSession()`. Os nomes estão
 * repetidos aqui de propósito em vez de importados de `src/`: o teste precisa quebrar se
 * alguém renomear uma chave sem migrar as sessões já gravadas no navegador dos usuários.
 * Uma constante compartilhada renomearia os dois lados junto e a suíte continuaria verde
 * sobre uma mudança que desloga todo mundo.
 */
const ACCESS_TOKEN_KEY = 'securityhub.accessToken';
const REFRESH_TOKEN_KEY = 'securityhub.refreshToken';
const CURRENT_USER_KEY = 'securityhub.currentUser';

/** `environment.apiUrl` é relativo, então o nginx do compose resolve o proxy. */
const API_PREFIX = '/api/v1';

export function demoPassword(): string {
  return Cypress.env('DEMO_PASSWORD') as string;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable {
      /**
       * Autentica pela API e entrega a sessão pronta à aplicação, sem passar pelo formulário.
       *
       * O `localStorage` é semeado dentro de `onBeforeLoad`, que roda **antes** de o bundle
       * da aplicação executar. Isso não é um detalhe de conveniência: `AuthService` chama
       * `restoreSession()` no próprio construtor, e o construtor roda uma única vez, no
       * bootstrap do Angular. Gravar as chaves depois do `cy.visit` deixaria a aplicação de
       * pé com sessão vazia, o `authGuard` mandaria para `/login`, e o teste falharia por um
       * motivo que não tem nada a ver com o que ele testa.
       *
       * Entrega a `AuthSession`, porque quase todo teste precisa do `accessToken` depois para
       * as chamadas diretas à API.
       */
      loginAs(email: string, visitPath?: string): Chainable<AuthSession>;

      /**
       * `cy.request` com o prefixo `/api/v1` e o header `Authorization` montados em um lugar
       * só. Sem `failOnStatusCode: false` embutido: quem espera um 403 ou um 404 diz isso
       * explicitamente na chamada, e um teste que esperava 200 continua falhando alto.
       */
      apiRequest<T = unknown>(options: ApiRequestOptions): Chainable<Cypress.Response<T>>;

      /**
       * Seleciona pelo `data-testid` que os componentes já declaram. Classe de CSS e texto
       * visível são decisões de design e mudam com o design; o `data-testid` é um contrato
       * com o teste e só muda quando alguém quer que o teste mude.
       */
      byTestId(id: string, options?: Partial<Cypress.Loggable & Cypress.Timeoutable>): Chainable<JQuery<HTMLElement>>;
    }
  }
}

Cypress.Commands.add('loginAs', (email: string, visitPath = '/dashboard') => {
  return cy
    .request<AuthSession>({
      method: 'POST',
      url: `${API_PREFIX}/auth/login`,
      body: { email, password: demoPassword() },
    })
    .then((response) => {
      const session = response.body;
      expect(session.accessToken, `token de ${email}`).to.be.a('string').and.not.be.empty;

      cy.visit(visitPath, {
        onBeforeLoad(win: Cypress.AUTWindow) {
          win.localStorage.setItem(ACCESS_TOKEN_KEY, session.accessToken);
          win.localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken);
          win.localStorage.setItem(CURRENT_USER_KEY, JSON.stringify(session.user));
        },
      });

      return cy.wrap(session, { log: false });
    });
});

Cypress.Commands.add('apiRequest', (options: ApiRequestOptions) => {
  const { token, url, headers, ...rest } = options;
  return cy.request({
    ...rest,
    url: `${API_PREFIX}${url}`,
    headers: {
      ...(headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
});

Cypress.Commands.add(
  'byTestId',
  (id: string, options?: Partial<Cypress.Loggable & Cypress.Timeoutable>) =>
    cy.get(`[data-testid="${id}"]`, options),
);
