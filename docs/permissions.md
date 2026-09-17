# Permission matrix

This document is the normative source for the permission matrix and records **where each rule is
applied in the code**, which is what matters in a security review.

## Principle

> Hiding a button in Angular is not a control.

Every rule below is applied in the backend. The interface only reflects what the backend already
guarantees; any direct API call with a token whose role is insufficient receives a 403 — and
that is tested.

## Roles

| Role | Intent |
| --- | --- |
| `ADMIN` | Administers the company: users, projects, assets, deletions and auditing. |
| `ANALYST` | Works the security backlog: creates, edits, assigns and classifies vulnerabilities. |
| `DEVELOPER` | Fixes what has been assigned to them: changes the status of **their own** items and comments. |
| `VIEWER` | Strictly read-only. |

## Matrix

| Action | ADMIN | ANALYST | DEVELOPER | VIEWER | Where it is applied |
| --- | :---: | :---: | :---: | :---: | --- |
| View dashboard, projects, assets, vulnerabilities | ✓ | ✓ | ✓ | ✓ | `SecurityConfig.anyRequest().authenticated()` |
| List users | ✓ | ✓ | — | — | `UserService.search` |
| Create/edit/delete a project | ✓ | — | — | — | `ProjectService.{create,update,delete}` |
| Create/edit/delete an asset | ✓ | — | — | — | `AssetService.{create,update,delete}` |
| Create/edit a vulnerability | ✓ | ✓ | — | — | `VulnerabilityService.{create,update}` |
| Delete a vulnerability | ✓ | — | — | — | `VulnerabilityService.delete` |
| Assign a vulnerability | ✓ | ✓ | — | — | `VulnerabilityService.assign` |
| Change **any** status | ✓ | ✓ | — | — | `VulnerabilityService.ensureCanChangeStatus` |
| Change the status of an item **assigned to oneself** | ✓ | ✓ | ✓ | — | `VulnerabilityService.ensureCanChangeStatus` |
| Comment | ✓ | ✓ | ✓ | — | `CommentService.create` |
| Edit a comment (author or ADMIN) | ✓ | author | author | — | `CommentService.ensureCanEdit` |
| Query the audit trail | ✓ | — | — | — | `AuditQueryService.search` |
| Export vulnerabilities as CSV | ✓ | ✓ | — | — | `VulnerabilityExportService.exportCsv` |
| Generate the executive PDF report | ✓ | ✓ | — | — | `ReportService.generateExecutivePdf` |
| View import history and preview | ✓ | ✓ | ✓ | ✓ | `SecurityConfig` (authenticated); `ScanImportService.{history,preview}` |
| Upload a scan report | ✓ | ✓ | — | — | `ScanImportService.upload` |
| Link a finding to an asset | ✓ | ✓ | — | — | `ScanImportService.mapFinding` |
| Confirm an import | ✓ | ✓ | — | — | `ScanImportService.confirm` |
| Discard a pending import | ✓ | ✓ | — | — | `ScanImportService.discard` |
| Attach a file to a vulnerability | ✓ | ✓ | ✓ | — | `AttachmentService.upload` |
| List and download attachments | ✓ | ✓ | ✓ | ✓ | `SecurityConfig` (authenticated) |
| Delete an attachment (author or ADMIN) | ✓ | author | author | — | `AttachmentService.ensureCanDelete` |
| Invite a user | ✓ | — | — | — | `InvitationService.invite` |
| List and revoke invitations | ✓ | — | — | — | `InvitationService.{list,revoke}` |
| Change a user's name | ✓ | — | — | — | `UserService.rename` |
| Change a user's role | ✓ | — | — | — | `UserService.changeRole` |
| Activate and deactivate a user | ✓ | — | — | — | `UserService.changeActive` |

## How the rules are expressed

### Role: `@PreAuthorize` on **service** methods
Never on controllers. The controller is a shell with no business rule, and putting the
annotation on the service guarantees that any caller — including a future internal service —
goes through the same control.

```java
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
public VulnerabilityResponse create(AuthenticatedUser current, VulnerabilityRequest request) { … }
```

### Ownership: an explicit check after loading the row
The `DEVELOPER` rule depends on the row's `assignedTo` value, which `@PreAuthorize` cannot see.
The annotation works as a coarse gatekeeper (it blocks the `VIEWER`) and ownership is verified
in the body:

```java
private void ensureCanChangeStatus(AuthenticatedUser current, Vulnerability vulnerability) {
    if (current.isAdmin() || current.hasRole(Role.ANALYST)) {
        return;
    }
    User assignee = vulnerability.getAssignedTo();
    if (assignee == null || !assignee.getId().equals(current.getId())) {
        throw new ForbiddenException("Você só pode alterar o status de vulnerabilidades atribuídas a você");
    }
}
```

**The order matters**: `require(...)` — which is already scoped by company — runs **before** the
ownership check. So a vulnerability belonging to another company gets a 404 and never a 403; a
403 would confirm that the row exists and would turn the API into an enumeration oracle.

`@PostAuthorize` was discarded: it evaluates after the body has already mutated the entity, and
the rollback would then depend on the relative order of the transactional interceptor and the
method security one.

### The `DEVELOPER` back door is closed
A `DEVELOPER` could try to change a status through `PUT /vulnerabilities/{id}` instead of the
status endpoint. Three independent locks prevent it:

1. `VulnerabilityRequest` **has no `status` field and no `resolvedAt` field** — the mapper has
   nothing to write. A reflection test fails if someone adds the field later.
2. `PUT` is `hasAnyRole('ADMIN','ANALYST')`, so a `DEVELOPER` never reaches the body.
3. `status` and `resolvedAt` are mutated in a single method, `changeStatus`.

### Isolation between companies
`companyId` **always** comes from the signed token, never from the body, the query string or a
header, and it is revalidated against the user's row on every request by
`JwtAuthenticationFilter`. Every repository finder carries the `companyId`, and every
specification starts with `Specs.company(...)`.

Access to another company's data returns **404, never 403**, on GET, PUT, PATCH and DELETE, and
the record simply does not appear in the listings.

## Identity rules that are not role rules

Some V2 rules do not depend on the caller's role, but on the state of the row or on who the
target is. They all live in the service, after the company-scoped lookup.

### An administrator cannot deactivate their own account

This holds **always**, even if there are other active administrators. It is a flat rule
precisely so that there is no path in which someone locks themselves out, and there is no
legitimate use case for the opposite. It answers 409.

### The company needs at least one active administrator

Demoting or deactivating the last active ADMIN answers 409. The count uses
`UserRepository.countByCompanyIdAndRoleAndActiveTrue`, which had existed since V1 with no
caller.

**A pending invitation does not count.** That is why an invitation lives in its own table and
the `users` row is only born on acceptance: if the invitation were a user row, an ADMIN
invitation that was never accepted would satisfy the count and an administrator could demote
themselves, leaving the company with no real administrator.

### The e-mail is not editable by an administrator

`UserUpdateRequest` carries only the name, and a reflection test fails if someone adds the field
later. The e-mail is the login identifier **and** the password recovery channel: an
administrator who repoints a colleague's address to their own inbox requests a reset and takes
over the account, with the victim seeing nothing.

### Changing a role and deactivating revoke the target's refresh tokens

The access token already dies on its own, because `JwtAuthenticationFilter` re-reads the user on
every request and rejects a divergent role or an inactive account. But the refresh token would
survive the administrative decision and would issue a new access token, so it is revoked
explicitly.

### Scan import follows the licence to create a vulnerability

Uploading a report, linking a finding to an asset, confirming and discarding are all
`hasAnyRole('ADMIN','ANALYST')`, declared on the `ScanImportService` methods — the controller
carries no role annotation at all. The criterion is simple: a confirmed import **is** the
creation of a batch of vulnerabilities, and whoever can create one through a form can create a
batch from a file. A `DEVELOPER` and a `VIEWER` get a 403 on all four.

Reading is open to any authenticated member of the company, like the vulnerabilities the import
is going to generate: `preview` and `history` have no `@PreAuthorize`, and the screen shows a
role without permission the same preview, without the buttons, with a notice saying they can
follow the review but not decide.

**`DELETE /scan-imports/{id}` is not ADMIN-only, unlike `DELETE /vulnerabilities/{id}`, and the
difference is not an oversight.** There, a backlog row is deleted, one that may have history, an
assignee and a discussion. Here a *proposal* is discarded: a pending import created nothing, and
giving up on it is the normal step for whoever uploaded the wrong file. Only a `PENDING` import
can be discarded — an already confirmed one answers 409 — so this endpoint never reaches an
existing vulnerability.

In Angular, `/imports/novo` is the only route of the feature with a `roleGuard`; `/imports` and
`/imports/:id` are open. This is the same principle as at the top of this document: the guard is
a convenience, and the real refusal happens again in the backend on every call.

### The DEVELOPER and attachments

Attaching is allowed for a DEVELOPER because attaching the evidence of a fix is the same act as
commenting, which they can already do. Deleting belongs to the author or an ADMIN — unlike a
comment, an attachment **has to** be removable: someone will upload the wrong file, and it may
contain data that should never have been sent.

## Test coverage

Existing negative tests: missing token, malformed token, no `Bearer` prefix, expired token,
token signed with another secret, token with a tampered payload, token whose `role` or
`companyId` diverges from the user's row, user deactivated mid-session, role without permission
(403), cross-company reads and writes between two companies (404), `DEVELOPER` on someone else's
item and on an unassigned item (403), `DEVELOPER` using `PUT` (403), and `ANALYST` editing
someone else's comment (403).

Added in V2: a refresh token reused outside the grace window (401, with the whole family
revoked), a token from another company, an access token presented at the refresh endpoint and
vice versa, password recovery answering identically for a known, an unknown and a deactivated
e-mail, an invitation to an address that already exists in another company (409), a pending
invitation not counting as an active administrator, self-deactivation (409), the last
administrator (409), an attempt to change the e-mail by reflection, export by a role without
permission (403 with a JSON body), an upload declaring one type and sending another (415), and
path traversal in the file name.

Added with scan import: `DEVELOPER` and `VIEWER` uploading a report, linking a finding,
confirming and discarding (403 on all four, with the complete multipart — an incomplete body
would give a 400 before the role rule ran), an import from another company on every endpoint
(404, including on the `DELETE`), a project from another company on upload (404), an asset from
another company on the link (404), confirming and discarding an already closed import (409),
linking a finding that already has an asset (409), and one company's history not listing the
other's imports.
