# Changelog

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows [SemVer](https://semver.org/).

## [1.3.0] — 2026-09-17

Redesign of the whole interface, from the sign-in screen to the listings, and a dark theme.

### Added

- **Dark theme**, with a toggle in the top bar. On the first visit it follows the system
  preference; after that the choice wins, stored per browser. The theme is applied before the
  first frame, so it does not flash white on every visit for someone using dark mode.
- `scripts/capture-screenshots.sh` regenerates the README images in both themes.

### Changed

- A design system of its own: colour, spacing, typography, shape, elevation and motion became
  tokens, and the screens stopped repeating loose values. Plus Jakarta Sans typography, with a
  fallback stack for a closed network.
- The top bar is no longer a full band in the brand colour. On a dashboard, 64px of strong
  colour at the top competes with the content, which is the product.
- Sign-in screen redesigned in two columns, with a brand panel that collapses on narrow
  screens. The five public screens share the same shell — credential screens that clash with
  each other are what a phishing page imitates.
- Severity, status and criticality badges unified into a single component, with an icon and a
  label in addition to the colour.

### Accessibility

- Every text-and-background combination was measured against WCAG before going in. The
  measurement failed the brand green with white text (3.77:1) and the translucent-background
  badges, which dropped to 3.90:1 on the row highlighted by the cursor; both were replaced.
- The Material palettes are now distinct per theme: the vault blue on the dark surface gave
  1.2:1, and the primary button all but disappeared.
- 44px touch targets, `prefers-reduced-motion` respected, and a visible focus ring that no
  longer deforms the element it surrounds.

### Fixed

- A vulnerability created by an import could not be deleted: the `scan_findings` row held the
  deletion back and the asset and the project were stuck along with it (migration `V10`).
- The first report upload failed on a fresh installation, because the directory mounted by
  Compose was created as root while the application runs as `securityhub`.
- A finding without CVSS printed "CVSS" with no number: the API omits the null field instead of
  sending it.
- The charts' equivalent tables, invisible, pushed horizontal scrolling into the content area.
- The dashboard's row of cards overflowed its container between 1160px and 1400px.

## [1.2.0] — 2026-09-17

Import of scanner reports, which was the last functional gap recorded in the README.

### Added

**Import**
- Reading of **Nmap (XML)**, **OWASP ZAP (JSON)** and **Nuclei (JSONL)** reports. From Nmap only
  the NSE script results go in: an open port is not a vulnerability, and importing it would fill
  the backlog with noise.
- Review before writing. The file is read once and stays in a pending state; the screen shows
  finding by finding with the matching asset, and nothing becomes a vulnerability until someone
  confirms.
- Linking a finding to an asset by the project's `identifier`, case-insensitively and ignoring
  surrounding whitespace. Whatever does not match is left to be chosen on screen — **no asset is
  created automatically.**
- Deduplication by the `sha256(scanner:ruleId:target:cve)` fingerprint, guaranteed by the
  partial unique index `(company_id, fingerprint)`. Severity and CVSS are left out on purpose:
  they change between scanner versions without the finding being a different one.
- Paginated import history, with the counters of each one.
- A single `SCAN_IMPORT` audit row per confirmed import, with the summary. Per-finding
  traceability stays in `scan_findings`, which is the right place for it.

### Decisions

- **Re-importing the same report creates nothing and changes nothing.** A repeated finding is
  counted and ignored, never reopened or overwritten: someone who changed a status, took on a
  finding or wrote a comment does not lose that work because of a new scan.
- **Import is synchronous, with a ceiling** (`securityhub.scan.max-findings`, 2000 by default).
  Above that the file is refused with a 400 before anything is written. An asynchronous job
  would solve a problem this product does not have.
- Only a pending import can be confirmed or discarded. Discarding deletes the file; confirming
  keeps it, because it is the document behind the vulnerabilities that were created.

### Security

- The XML parser refuses DTDs and external entities, which closes XXE in the format Nmap emits
  with a DOCTYPE. The test asserts that the document is refused, not that the content is absent:
  the JDK already blocks entities in an attribute value on its own, so a test written over
  attributes would stay green with the protection removed.
- Uploading, mapping, confirming and discarding require ADMIN or ANALYST; the history and the
  review are readable by any member of the company. A resource from another company still
  answers 404.

## [1.1.0] — 2026-09-17

Closes the identity gaps and adds the deliverables that were missing.

### Added

**Session**
- Rotating refresh token, stored only as a digest, with family revocation and reuse detection.
  The interface renews the session and repeats the failed request, instead of sending the user
  back to the login screen in the middle of a task.
- `POST /auth/logout`, which revokes the family on the server.
- Password recovery through a single-use link, with expiry, without revealing whether the
  account exists.

**Users**
- Invitation by e-mail, with an acceptance that creates the account and already returns a
  session.
- Role change, name change and activation/deactivation, with the last-active-administrator and
  self-deactivation rules.
- Administration screen for users and pending invitations.

**Content**
- Attachments on vulnerabilities, with a type allowlist verified from the bytes, a size limit, a
  generated name on disk and download as `attachment`.
- Export of the vulnerability listing as CSV, with the same filters as the screen.
- Executive PDF report, with the dashboard figures.

**Operations**
- Metrics at `/actuator/prometheus` and structured JSON logging in the production profile.
- E2E tests with Cypress on the critical flows.
- MailHog in Compose, so the e-mail flow works without an external provider.

### Fixed

- **The sign-out button did not revoke the session on the server.** The call was not subscribed,
  and a cold observable does not fire: the local session went away while the refresh token
  family stayed valid until it expired.
- **`/actuator/metrics` was readable by any authenticated role**, including the read-only one.
  Metrics are now served on a separate management port, published on loopback only. The
  administrator here is the customer's, not the infrastructure operator, and the role matrix has
  no way to express that difference.
- The e-mail health indicator brought `/actuator/health` down when SMTP was unavailable,
  contradicting the fact that sending is deliberately failure-tolerant.
- On binary responses, the error reached the user as a generic message next to the real message,
  because the body could not be read as JSON by the interceptor.

### Security

- Credential material is stored as SHA-256, and the database refuses any other form.
- An attachment's type is determined from the bytes; the type declared by the client is ignored.
- The export neutralises formulas and always quotes the cells, because the prefix alone does not
  stop a cell containing a comma from breaking the row.
- No critical vulnerabilities in the production dependencies.

### Infrastructure

- Every CI job now declares a timeout, and the browser that puppeteer downloads is cached.
  Without that, a stalled install occupied a runner up to the six-hour ceiling.

[1.1.0]: https://github.com/coopas/SecurityHub/releases/tag/v1.1.0

## [1.0.0] — 2026-09-17

First publishable version. Multi-company management of security assets and vulnerabilities, with
role authorisation, an audit trail and a dashboard.

### Added

**Authentication and tenant**
- Transactional registration of a company with its first administrator, and login with a JWT
  signed with HS256 and passwords with BCrypt cost 12.
- JWT filter that revalidates signature, expiry, role, company and user status on every request.
- Per-company isolation in every repository: the `companyId` always comes from the token, and a
  resource from another company answers 404, never 403.
- Role authorisation (ADMIN, ANALYST, DEVELOPER, VIEWER) applied on service methods.

**Domain**
- CRUD for projects, assets and vulnerabilities with search, filters, sorting and server-side
  pagination, with a sort allowlist and a page-size limit.
- Assignment restricted to an active user of the same company.
- Status transition with `resolvedAt` filled in on entering `RESOLVED` and cleared on leaving,
  guaranteed by a database constraint as well.
- Comments on vulnerabilities, with editing restricted to the author or an administrator.
- Deletion of a parent that still has children blocked with a readable conflict, instead of a
  silent cascade.

**Auditing**
- Append-only trail with actor, time, entity, before and after values, and source address,
  sanitising sensitive fields by key name.
- Paginated query with filters, restricted to administrators.

**Dashboard**
- Summary, distribution by severity and by status, and a daily time series of open and resolved
  findings, with three indexes added from real measurement.

**Interface**
- Angular 16 with lazy loading per feature: login, registration, dashboard, projects, assets,
  vulnerabilities and auditing, plus the 403 and 404 pages.
- Filters reflected in the URL, loading, empty and error states on every asynchronous screen,
  confirmation before deleting, and severity and status always with an icon and text.

**Infrastructure and documentation**
- Docker Compose with PostgreSQL 15, backend and frontend, all with healthchecks.
- Idempotent seed for the `demo` profile with two companies and one user per role.
- Architecture, ER diagram, permission matrix, API examples, dependency analysis, ADRs and
  `.http` and Postman collections.
- Smoke test script covering the full flow and the isolation between companies.

### Fixed

- **The audit trail's `recordIndependently` was not independent.** The `AuditLogWriter` methods
  were package-private, and Spring only applies `@Transactional` to public methods, so
  `REQUIRES_NEW` was silently ignored. As a consequence, a failed login left no trace, and a
  failure to write the trail brought the caller's request down with a 500.
- **Optional filters broke when left blank.** PostgreSQL does not infer the type of a null
  parameter in `:param is null or ...`; the filters are now built with the Criteria API.
- **`overdue=false` omitted vulnerabilities without a due date.** Negating only the date
  comparison results in `UNKNOWN` in SQL; the predicate is now negated as a complete
  conjunction.
- **The listing's search did not reapply an identical term** after clearing the filters, because
  `distinctUntilChanged` held a value the route had already overwritten.

### Security

- PostgreSQL driver raised from 42.3.8 to 42.7.7, above CVE-2024-1597 (CVSS 10.0). It was not
  exploitable in this application, which uses the default extended query mode.
- No critical vulnerabilities in the production dependencies. The remaining ones are analysed
  and justified in `docs/security-dependencies.md`.

### Known limitations

- No refresh token, password recovery or user management in the interface. Login uses a
  short-lived access token and users are created when the company registers.
- `GET /users` and the dashboard distributions return a plain array instead of the paginated
  envelope, because they are fixed-size aggregates. The decision is recorded in the code.

[1.0.0]: https://github.com/coopas/SecurityHub/releases/tag/v1.0.0
