/// <reference types="cypress" />

/**
 * Three commands, and only three. Each one exists because the alternative would be repeating
 * the same decision in five files and letting it diverge in one of them.
 */

/** Mirrors the backend's `AuthResponse`. Only the fields the suite actually uses. */
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
  /**
   * Bearer token. Omitted, the call goes with no `Authorization` — that is how a 401 is tested.
   */
  token?: string;
  /** Path from `/api/v1` onwards, with the leading slash: `/vulnerabilities/7`. */
  url: string;
}

/**
 * The three keys `AuthService` reads in its constructor, in `restoreSession()`. The names are
 * repeated here deliberately instead of imported from `src/`: the test has to break if someone
 * renames a key without migrating the sessions already written to the users' browsers. A shared
 * constant would rename both sides together and the suite would stay green over a change that
 * logs everybody out.
 */
const ACCESS_TOKEN_KEY = 'securityhub.accessToken';
const REFRESH_TOKEN_KEY = 'securityhub.refreshToken';
const CURRENT_USER_KEY = 'securityhub.currentUser';

/** `environment.apiUrl` is relative, so the compose nginx resolves the proxy. */
const API_PREFIX = '/api/v1';

export function demoPassword(): string {
  return Cypress.env('DEMO_PASSWORD') as string;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable {
      /**
       * Authenticates through the API and hands the application a ready session, without going
       * through the form.
       *
       * `localStorage` is seeded inside `onBeforeLoad`, which runs **before** the application
       * bundle executes. That is not a convenience detail: `AuthService` calls
       * `restoreSession()` in its own constructor, and the constructor runs exactly once, at
       * Angular's bootstrap. Writing the keys after the `cy.visit` would leave the application
       * standing with an empty session, `authGuard` would send it to `/login`, and the test
       * would fail for a reason that has nothing to do with what it tests.
       *
       * Yields the `AuthSession`, because almost every test needs the `accessToken` afterwards
       * for the direct API calls.
       */
      loginAs(email: string, visitPath?: string): Chainable<AuthSession>;

      /**
       * `cy.request` with the `/api/v1` prefix and the `Authorization` header assembled in a
       * single place. No built-in `failOnStatusCode: false`: whoever expects a 403 or a 404 says
       * so explicitly at the call site, and a test that expected 200 keeps failing loudly.
       */
      apiRequest<T = unknown>(options: ApiRequestOptions): Chainable<Cypress.Response<T>>;

      /**
       * Selects by the `data-testid` the components already declare. A CSS class and visible
       * text are design decisions and change with the design; the `data-testid` is a contract
       * with the test and only changes when someone wants the test to change.
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
