# Dependency analysis

The project's security policy requires dependencies with no known critical vulnerabilities at
delivery time, with justified exceptions recorded. This document is that record.

Analysis date: **2026-09-17**. Reviewed at the end of V2.

## Commands

```bash
cd frontend && npm audit --omit=dev --audit-level=high   # dependencies that reach the bundle
cd frontend && npm audit                                  # includes devDependencies
cd backend  && ./mvnw verify -Pdependency-check           # OWASP, fails at CVSS >= 9
```

`--omit=dev` is the reading that matters for production risk: a vulnerability in
`karma-jasmine` is not served to anyone. The full `npm audit` number is reported below for
transparency, not as a measure of risk.

## Result

| Scope | Critical | High | Moderate | Low |
| --- | :---: | :---: | :---: | :---: |
| Frontend, production (`--omit=dev`) | **0** | 3 | 7 | 0 |
| Frontend, including dev | 1 | 33 | 25 | 7 |

**The §9 criterion is met: zero critical vulnerabilities in the production dependencies.**

## Recorded exception: the 10 production findings are Angular's own

The 10 production findings are in `@angular/core`, `@angular/common` and `@angular/compiler`,
and the remaining Angular packages appear only as transitive dependents of those. `npm audit`
offers a single fix: `npm audit fix --force`, which installs **`@angular/core@22.1.7`**.

That is refused, and the justification is the project's own constraint, which pins
**Angular 16** as an immutable part of the stack. Swapping to Angular 22 would be a jump of six
majors, would require rewriting the build, the tests and the templates, and contradicts the
decision recorded in `docs/adr/0001`. There is no fixed version within the 16 line.

### Actual applicability of the findings to this application

Recording an exception without assessing the risk would be theatre. Each advisory family was
checked against the code:

| Advisory family | Required vector | Present here? |
| --- | --- | --- |
| Sanitisation bypass / XSS via SVG, MathML, namespace, two-way binding, host bindings | Rendering untrusted HTML through `[innerHTML]`, `DomSanitizer` or `bypassSecurityTrust*` | **No.** No occurrence of `innerHTML`, `DomSanitizer` or `bypassSecurityTrust` in `frontend/src`. Every value coming from the API is interpolated as text. |
| `HttpTransferCache`: cache poisoning, 32-bit key collision, credentialed request leak, DOM clobbering during hydration | SSR with client hydration | **No.** The application is purely client-side: there is no `@angular/platform-server` and no `provideClientHydration`. |
| XSS via i18n (`$localize`, event handler attributes) | Use of Angular's i18n | **No.** The application does not use Angular i18n; the texts are Portuguese literals in the templates. |
| OOM DoS in `formatDate` and `digitsInfo` | Passing a user-controlled format to those APIs | **No.** Neither is used. The dashboard's `formatDate` is a component method of its own that slices the `yyyy-MM-dd` string, without involving Angular. |
| XSRF token leak through a protocol-relative URL | Use of Angular's `HttpClientXsrfModule` | **No.** Authentication is stateless JWT and CSRF is disabled in the backend for that reason. The `AuthInterceptor` attaches the token only when the URL starts with `environment.apiUrl`, so it never sends a credential to a third-party origin. |

**Conclusion:** none of the 10 findings has a reachable vector in this application. The exposure
is theoretical and follows from the package being in the tree, not from the code exercising the
vulnerable path.

### What would change the conclusion

This analysis stops being valid if someone introduces `[innerHTML]`, `bypassSecurityTrust*`, SSR
with hydration, Angular i18n or `HttpClientXsrfModule`. Any of those makes upgrading Angular a
release blocker rather than an acceptable exception.

### The numbers that include devDependencies

The 66 findings of the full `npm audit` (1 critical) live in the build and test chain —
`@angular-devkit/build-angular`, `karma`, `webpack-dev-server`, `puppeteer` and transitives.
None of that is served to the browser or bundled: `frontend/Dockerfile` is multi-stage and the
final image is an `nginx:alpine` with only the static artifacts from `dist/`. The risk is to a
developer machine and a CI runner, not to the published application. Fixing them requires the
same major upgrade refused above.

## Dependencies added in V2

| Dependency | Scope | Licence | Why |
| --- | --- | --- | --- |
| `spring-boot-starter-mail` | runtime | Apache-2.0 | Delivery of password recovery and invitation links (`docs/adr/0007`). |
| `com.github.librepdf:openpdf` | runtime | LGPL-2.1 / MPL-2.0 | Executive PDF report (`docs/adr/0008`). |
| `org.apache.pdfbox:pdfbox` | **test** | Apache-2.0 | Only for `PDFTextStripper`: a PDF that prints `Injeç?o` is a defect no byte-level assertion catches. |
| `cypress` | **devDependency** | MIT | E2E tests of the critical flows. Does not go into the bundle. |

### Licence obligations

OpenPDF is dual LGPL/MPL. The LGPL obligations attach to the distribution of a combined work and
are met by depending on the published artifact without modification; this is a server
application, which is not distributed.

### Embedded font

`backend/src/main/resources/fonts/DejaVuSans.ttf` is redistributed under the Bitstream Vera /
Arev licence, whose text sits beside the file in `LICENSE-DejaVu.txt`. Redistribution is
permitted; **including the licence text is the part that is usually forgotten**.

### MailHog is not an application dependency

The inbox container exists only in the development `docker-compose.yml`. There is no artifact of
it in the build, and the application speaks plain SMTP to any relay.

## Backend

The `dependency-check` profile is opt-in because it downloads the NVD database, which makes the
build too slow to run on every commit. It fails at `failBuildOnCVSS >= 9`.

The file `backend/dependency-check-suppressions.xml` is referenced by `pom.xml` and now exists —
before it was absent, so the profile would fail when executed. It is empty of suppressions by
choice: every future suppression must come with a comment explaining why the finding does not
apply.

Keeping **Spring Boot 2.7.18** is also a conscious exception, recorded in `docs/adr/0001`: the
2.7 line is out of open-source (OSS) support, so security fixes may require pinning individual
dependency versions above what the BOM manages — which is exactly what was already done with
Flyway (`docs/adr/0002`). Migrating to Spring Boot 3 would require Java 17 and `jakarta.*`,
which the project's stack constraints forbid.
