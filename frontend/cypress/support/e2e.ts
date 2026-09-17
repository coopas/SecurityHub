/**
 * SecurityHub end-to-end suite.
 *
 * ============================================================================
 * HOW TO WRITE A TEST HERE WITHOUT CREATING A FALSE RED
 * ============================================================================
 *
 * The target is the `docker compose` stack with the `demo` profile seed, and that seed is dated
 * **relatively**: vulnerabilities are born with `discoveredAt` and `dueDate` computed from
 * `Instant.now()` at the moment the container came up. That means the data set changes on its
 * own as the clock moves — a vulnerability that is within its deadline today is overdue tomorrow,
 * and the number on the dashboard's "Atrasadas" card is different every day. A test that pins
 * those numbers breaks without anything in the product having changed, and a test that breaks on
 * its own is a test somebody will end up switching off.
 *
 * The five rules below are what keeps this suite deterministic. They hold for every file in
 * `cypress/e2e`.
 *
 * 1. **Never assert an absolute number that depends on the clock.** Assert a *relation* between
 *    two values the product computes by different paths. The canonical example is the
 *    dashboard's "Atrasadas" card against the `totalElements` of
 *    `GET /vulnerabilities?overdue=true`: they are the database aggregation and the paginated
 *    listing, and a divergence between them is the most visible defect this screen can have. The
 *    relation is true on any day; the number is not.
 *
 * 2. **Never assert a formatted date.** "17/09/2026" depends on the clock, on the container's
 *    timezone and on the browser's locale. If the date matters, assert that the field exists and
 *    is not empty, or compare it with the value the API itself returned.
 *
 * 3. **Create the data you assert on.** Every row created by a test carries a unique title —
 *    the pattern is `Cypress ${Date.now()}` — and is deleted in the `after()` of its own file.
 *    That way two tests never fight over the same row, one run leaves no residue for the next,
 *    and the assertion does not depend on the seed having exactly today's content.
 *
 * 4. **Authenticate through the API, except in the login form test.** `cy.loginAs()` does the
 *    `POST /auth/login` and seeds `localStorage` before the application bootstraps. Going through
 *    the form in every test would add to each of them the chance of failing for a reason that
 *    `01-login.cy.ts` already covers — and that only it should cover.
 *
 * 5. **Permission is tested on the API, not on the CSS.** Hiding a button is not a control: it
 *    is convenience. A test that only checks the button's absence would be testing the wrong
 *    layer, and would pass untouched with the backend completely open. Every permission
 *    assertion in this suite has a pair: the absence of the affordance **and** the 403 of the
 *    corresponding route, called directly with the role's token.
 */

import './commands';

/**
 * The `ResizeObserver loop limit exceeded` comes from Angular Material (sidenav and mat-table
 * remeasuring in the same frame). It is browser noise, not an application exception: none of our
 * code is on the stack and nothing breaks. Any other unhandled exception still brings the test
 * down, which is exactly what you want from a real error.
 */
Cypress.on('uncaught:exception', (error) => {
  if (/ResizeObserver loop/i.test(error.message)) {
    return false;
  }
  return undefined;
});
