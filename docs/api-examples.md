# API examples

Practical reference for the whole SecurityHub API, derived from the code (`backend/src/main/java/com/securityhub`).
Each endpoint gives the method, the path, who may call it (matrix in `docs/permissions.md`), the request body where
there is one and a realistic response.

To run the calls: `docs/http/securityhub.http` (REST Client / IntelliJ) and
`docs/http/SecurityHub.postman_collection.json` (Postman). The formal, browsable contract is in the Swagger UI at
`http://localhost:8080/swagger-ui.html`.

---

## Conventions

| Item | Value |
| --- | --- |
| Prefix | `/api/v1` (local base: `http://localhost:8080/api/v1`) |
| Format | camelCase JSON, `Content-Type: application/json` |
| Authentication | `Authorization: Bearer <accessToken>` |
| Dates | ISO-8601 in UTC (`2026-09-17T12:00:00Z`); `trend` uses civil dates `YYYY-MM-DD` |
| Tenant | Never in the body or the query string. Always taken from the JWT (`companyId`) |

### Null fields do not appear

`spring.jackson.default-property-inclusion: non_null` (see `application.yml`). A null field is **omitted** from the
payload, not serialised as `null`. An unresolved vulnerability simply **has no `resolvedAt` key**; an asset without
an `identifier` has no `identifier` key; the login `refreshToken` (V2, not implemented yet) does not appear in the
response.

The converse holds for primitive fields: `overdue`, `editable`, `active`, `assetCount`, `vulnerabilityCount`,
`expiresIn` and every dashboard counter are primitive `boolean`/`long` and are therefore **always** present,
including when they are `false` or `0`.

On the request side, `null` is meaningful in exactly one place: `PATCH /vulnerabilities/{id}/assignee` with
`{"userId": null}` is how an item is unassigned.

### Paginated listing envelope

Every paginated listing (`/projects`, `/assets`, `/vulnerabilities`, `/vulnerabilities/{id}/comments`,
`/audit-logs`, `/scan-imports`) returns a `PageResponse`:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "sort": "createdAt,desc"
}
```

`sort` echoes the ordering **actually applied** by the server, after sanitisation — it is what you check to see
whether the `sort` you sent was accepted (see the allowlist below). With multiple orderings the pairs come
separated by `;`, for example `"createdAt,asc;id,asc"`. If the ordering is empty, the `sort` key is omitted
(`non_null` rule).

Two listings do **not** use this envelope, on purpose:

- `GET /users` returns a plain array: the list is short, limited to the company, and exists to populate an
  assignee selector.
- `GET /dashboard/severity-distribution` and `GET /dashboard/status-distribution` return fixed arrays of 4
  elements (one per enum value). Wrapping a constant-size aggregate in `page`/`size`/`totalPages` would give the
  client five constant fields and no possible action. The deviation is recorded in the javadoc of
  `SeverityDistributionResponse`.

### Error envelope

Every failure — validation, authentication, authorisation, conflict, unexpected error — answers with `ApiError`:

```json
{
  "timestamp": "2026-09-17T12:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Dados inválidos",
  "path": "/api/v1/vulnerabilities",
  "fieldErrors": [{ "field": "title", "message": "é obrigatório" }],
  "traceId": "7f3a1c9e4b2d5a68"
}
```

`fieldErrors` exists only on validation errors; on the others the key is omitted. `traceId` matches the
`X-Request-Id` response header and the corresponding log line.

Possible values of `code` (`ErrorCode`): `VALIDATION_ERROR`, `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`,
`NOT_FOUND`, `CONFLICT`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`.

### Pagination and sorting

| Parameter | Default | Note |
| --- | --- | --- |
| `page` | `0` | Negative values are raised to `0` |
| `size` | `20` | Clamped to the range `[1, 100]` (`PageableSupport.MAX_PAGE_SIZE`) |
| `sort` | per module | `sort=field,asc` or `sort=field,desc`; repeat the parameter to sort by more than one field |

**The sort allowlist fails silently, by design.** `PageableSupport.sanitize` discards any property outside the
module's list; if nothing is left, it applies the default ordering. A `sort=passwordHash,asc` or
`sort=company.name,asc` does not become a 400 or a 500 — it becomes the default ordering. The reason is that the
property sent by the client reaches Spring Data as an entity attribute path; accepting any string would turn the
parameter into an arbitrary path into unintended associations. Because the discard is silent, **check the `sort`
key of the response** to see what the server actually used.

| Module | Sortable properties | Default ordering |
| --- | --- | --- |
| Projects | `name`, `status`, `createdAt`, `updatedAt` | `createdAt,desc` |
| Assets | `name`, `type`, `environment`, `criticality`, `createdAt`, `updatedAt` | `createdAt,desc` |
| Vulnerabilities | `title`, `severity`, `status`, `cvssScore`, `discoveredAt`, `dueDate`, `resolvedAt`, `createdAt`, `updatedAt` | `createdAt,desc` |
| Comments | `createdAt` | `createdAt,asc;id,asc` |
| Audit trail | `createdAt`, `action`, `entityType` | `createdAt,desc` |
| Imports | `createdAt`, `updatedAt`, `status`, `format`, `sizeBytes`, `totalFindings` | `createdAt,desc` |
| Users | — (no pagination and no `sort`) | always `name,asc` |

The ascending ordering of comments is deliberate: a discussion is read from oldest to newest, unlike the rest of
the API. The `id` comes in as a tie-breaker so that two comments written in the same microsecond do not swap pages
between two reads.

### Enums

| Enum | Values |
| --- | --- |
| `Role` | `ADMIN`, `ANALYST`, `DEVELOPER`, `VIEWER` |
| `ProjectStatus` | `ACTIVE`, `ARCHIVED` |
| `AssetType` | `API`, `SERVER`, `WEBSITE`, `DATABASE`, `WORKSTATION`, `OTHER` |
| `Environment` | `PRODUCTION`, `STAGING`, `DEVELOPMENT`, `TEST` |
| `Criticality` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `Severity` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `VulnerabilityStatus` | `OPEN`, `IN_PROGRESS`, `RESOLVED`, `ACCEPTED_RISK` |
| `ScanFormat` | `NMAP_XML`, `ZAP_JSON`, `NUCLEI_JSONL` |
| `ScanImportStatus` | `PENDING`, `CONFIRMED`, `DISCARDED` |
| `ScanFindingStatus` | `MATCHED`, `UNMATCHED`, `DUPLICATE`, `IMPORTED`, `SKIPPED` |
| `AuditAction` | `LOGIN`, `LOGIN_FAILED`, `REGISTER`, `CREATE`, `UPDATE`, `DELETE`, `STATUS_CHANGE`, `ASSIGN`, `COMMENT`, `PASSWORD_RESET`, `USER_INVITED`, `USER_UPDATED`, `EXPORT`, `SCAN_IMPORT` |

`Severity` and `Criticality` list the same four levels but are distinct enums: criticality describes how much an
asset matters, severity how serious a finding is.

An invalid enum value in a query string or a body answers **400 `BAD_REQUEST`** ("Requisição malformada"), with no
`fieldErrors`, because the failure happens during deserialisation, before validation.

---

## Authentication

| Method | Endpoint | Access |
| --- | --- | --- |
| POST | `/auth/register` | public |
| POST | `/auth/login` | public |
| GET | `/auth/me` | authenticated (any role) |

### POST /auth/register

Creates the company and its first user, always with the `ADMIN` role. It is the only way to create a company.

Validation: `companyName` and `name` from 2 to 120 characters, a valid `email` of up to 180, `password` from **10
to 100 characters**.

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "companyName": "Acme Segurança",
  "name": "Administrador",
  "email": "admin@acme.test",
  "password": "uma-senha-suficientemente-longa"
}
```

`201 Created`

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.<payload>.<assinatura>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": 12,
    "name": "Administrador",
    "email": "admin@acme.test",
    "role": "ADMIN",
    "active": true,
    "companyId": 4,
    "companyName": "Acme Segurança"
  }
}
```

Note what is **not** there: `refreshToken` is null in the MVP (refresh is V2) and disappears through the
`non_null` rule; `lastLoginAt` does not exist yet for a freshly created user; the user's `createdAt` appears from
the `/auth/me` read onwards. `expiresIn` is in seconds and reflects `securityhub.jwt.expiration-minutes` (60 by
default).

The e-mail is unique **globally**, not per company (see `docs/adr/0004-global-email-uniqueness.md`), so an e-mail
already used in another company answers `409 CONFLICT` with "E-mail já cadastrado".

### POST /auth/login

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "admin@demo.test",
  "password": "Demo@SecurityHub2026"
}
```

`200 OK` — same shape as register, now with `lastLoginAt` filled in on the next `/auth/me`.

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.<payload>.<assinatura>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": 1,
    "name": "Ana Ribeiro",
    "email": "admin@demo.test",
    "role": "ADMIN",
    "active": true,
    "companyId": 1,
    "companyName": "Demo Security"
  }
}
```

Wrong credentials, a non-existent e-mail and a deactivated user all answer the same `401 UNAUTHORIZED` with
"Credenciais inválidas". When the e-mail does not exist the service still checks the password against a throwaway
hash so that the response time does not tell the two cases apart — the endpoint is not usable for enumerating
accounts.

### GET /auth/me

```http
GET /api/v1/auth/me
Authorization: Bearer {{accessToken}}
```

`200 OK`

```json
{
  "id": 1,
  "name": "Ana Ribeiro",
  "email": "admin@demo.test",
  "role": "ADMIN",
  "active": true,
  "companyId": 1,
  "companyName": "Demo Security",
  "lastLoginAt": "2026-09-17T12:00:03Z",
  "createdAt": "2026-09-01T09:14:22Z"
}
```

---

## Projects

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/projects` | any authenticated role |
| GET | `/projects/{id}` | any authenticated role |
| POST | `/projects` | `ADMIN` |
| PUT | `/projects/{id}` | `ADMIN` |
| DELETE | `/projects/{id}` | `ADMIN` |

### GET /projects

`GET /projects?page=&size=&sort=&search=&status=`

| Parameter | Type | Effect |
| --- | --- | --- |
| `search` | text | case-insensitive `contains` on `name` **or** `description` |
| `status` | `ProjectStatus` | exact equality |
| `page`, `size`, `sort` | — | see "Pagination and sorting"; sortable: `name`, `status`, `createdAt`, `updatedAt` |

```http
GET /api/v1/projects?page=0&size=20&sort=name,asc&search=portal&status=ACTIVE
Authorization: Bearer {{accessToken}}
```

`200 OK`

```json
{
  "content": [
    {
      "id": 7,
      "name": "Portal do Cliente",
      "description": "Aplicação web voltada ao cliente final",
      "status": "ACTIVE",
      "assetCount": 3,
      "createdByName": "Ana Ribeiro",
      "createdAt": "2026-09-10T14:02:11Z",
      "updatedAt": "2026-09-16T08:41:07Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "sort": "name,asc"
}
```

`assetCount` comes from a single grouped query for the whole page, not from a mapped collection — which is why
there is no N+1 when listing projects. A project without a description does not carry the `description` key.

### POST /projects

`status` is optional on creation; absent means `ACTIVE`. `name` is 2 to 140 characters, `description` up to 2000.
There is no `companyId` in the body, neither here nor in any other endpoint: the tenant comes from the token.

```http
POST /api/v1/projects
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "name": "Portal do Cliente",
  "description": "Aplicação web voltada ao cliente final",
  "status": "ACTIVE"
}
```

`201 Created`

```json
{
  "id": 7,
  "name": "Portal do Cliente",
  "description": "Aplicação web voltada ao cliente final",
  "status": "ACTIVE",
  "assetCount": 0,
  "createdByName": "Ana Ribeiro",
  "createdAt": "2026-09-17T12:01:00Z",
  "updatedAt": "2026-09-17T12:01:00Z"
}
```

A repeated name in the same company (case-insensitive comparison) answers `409 CONFLICT`: "Já existe um projeto
com esse nome nesta empresa".

### GET /projects/{id}

`200 OK` with the same object as above. An id from another company answers `404` — see "Isolation between
companies".

### PUT /projects/{id}

Full replacement: `name` is required, an absent `description` **clears** the description, and an absent `status`
**preserves** the current status (it is the only field whose absence does not reset the value).

```http
PUT /api/v1/projects/7
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "name": "Portal do Cliente",
  "description": "Descrição revisada",
  "status": "ARCHIVED"
}
```

`200 OK` with the updated project (same shape as the `POST`).

### DELETE /projects/{id}

`204 No Content`, with no body.

A project that still has assets answers `409 CONFLICT` with the count in the message: "O projeto possui 3
ativo(s) e não pode ser excluído". The product rule is not to delete children through a silent cascade; the
foreign key has no `ON DELETE CASCADE`, so the alternative would be a raw integrity violation instead of a
readable conflict.

---

## Assets

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/assets` | any authenticated role |
| GET | `/assets/{id}` | any authenticated role |
| POST | `/assets` | `ADMIN` |
| PUT | `/assets/{id}` | `ADMIN` |
| DELETE | `/assets/{id}` | `ADMIN` |

### GET /assets

`GET /assets?page=&size=&sort=&search=&projectId=&type=&environment=&criticality=`

| Parameter | Type | Effect |
| --- | --- | --- |
| `search` | text | case-insensitive `contains` on `name`, `description` **or** `identifier` |
| `projectId` | `Long` | assets of one project |
| `type` | `AssetType` | exact equality |
| `environment` | `Environment` | exact equality |
| `criticality` | `Criticality` | exact equality |
| `page`, `size`, `sort` | — | sortable: `name`, `type`, `environment`, `criticality`, `createdAt`, `updatedAt` |

```http
GET /api/v1/assets?projectId=7&environment=PRODUCTION&criticality=CRITICAL&sort=criticality,desc
Authorization: Bearer {{accessToken}}
```

`200 OK`

```json
{
  "content": [
    {
      "id": 21,
      "name": "API de pagamentos",
      "description": "Processa cobranças e estornos",
      "type": "API",
      "identifier": "api.pagamentos.demo.test",
      "environment": "PRODUCTION",
      "criticality": "CRITICAL",
      "projectId": 7,
      "projectName": "Portal do Cliente",
      "vulnerabilityCount": 4,
      "createdAt": "2026-09-10T14:20:33Z",
      "updatedAt": "2026-09-10T14:20:33Z"
    },
    {
      "id": 22,
      "name": "Estação de suporte",
      "type": "WORKSTATION",
      "environment": "PRODUCTION",
      "criticality": "MEDIUM",
      "projectId": 7,
      "projectName": "Portal do Cliente",
      "vulnerabilityCount": 0,
      "createdAt": "2026-09-11T10:02:00Z",
      "updatedAt": "2026-09-11T10:02:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "sort": "criticality,desc"
}
```

The second asset shows the `non_null` rule in action: with no description and no identifier, neither key
appears.

### POST /assets

`projectId`, `name`, `type`, `environment` and `criticality` are required. `identifier` is optional (up to 255
characters) and a blank string is normalised to absent.

```http
POST /api/v1/assets
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "projectId": 7,
  "name": "API de pagamentos",
  "description": "Processa cobranças e estornos",
  "type": "API",
  "identifier": "api.pagamentos.demo.test",
  "environment": "PRODUCTION",
  "criticality": "CRITICAL"
}
```

`201 Created` with the asset object and `vulnerabilityCount: 0`.

Specific conflicts and errors:

- A repeated identifier **within the same project** (case-insensitively): `409 CONFLICT` — "Já existe um ativo
  com esse identificador neste projeto". An absent identifier never conflicts: several assets of the same
  project may have none.
- A `projectId` from another company or a non-existent one: `404 NOT_FOUND` — "Projeto 999 não encontrado".

### PUT /assets/{id}

Accepts moving the asset to another project **of the same company** by sending a different `projectId`; the
unique-identifier check then runs against the destination project. A `projectId` from another company is a `404`.

### DELETE /assets/{id}

`204 No Content`. An asset with vulnerabilities answers `409 CONFLICT`: "O ativo possui 4 vulnerabilidade(s) e
não pode ser excluído".

---

## Vulnerabilities

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/vulnerabilities` | any authenticated role |
| GET | `/vulnerabilities/{id}` | any authenticated role |
| POST | `/vulnerabilities` | `ADMIN`, `ANALYST` |
| PUT | `/vulnerabilities/{id}` | `ADMIN`, `ANALYST` |
| DELETE | `/vulnerabilities/{id}` | `ADMIN` |
| PATCH | `/vulnerabilities/{id}/status` | `ADMIN`, `ANALYST`; `DEVELOPER` only on an item assigned to them |
| PATCH | `/vulnerabilities/{id}/assignee` | `ADMIN`, `ANALYST` |

### GET /vulnerabilities

`GET /vulnerabilities?page=&size=&sort=&search=&projectId=&assetId=&severity=&status=&assignedTo=&overdue=`

| Parameter | Type | Effect |
| --- | --- | --- |
| `search` | text | case-insensitive `contains` on `title`, `description` **or** `cve` |
| `projectId` | `Long` | reaches the project through the asset (`asset.project.id`), not through a denormalised column |
| `assetId` | `Long` | exact equality |
| `severity` | `Severity` | exact equality |
| `status` | `VulnerabilityStatus` | exact equality |
| `assignedTo` | `Long` | assignee id |
| `overdue` | `boolean` | see below |
| `page`, `size`, `sort` | — | sortable: `title`, `severity`, `status`, `cvssScore`, `discoveredAt`, `dueDate`, `resolvedAt`, `createdAt`, `updatedAt` |

**Definition of `overdue`**: `dueDate != null` **and** `dueDate < now` **and** the status is `OPEN` or
`IN_PROGRESS`. A `RESOLVED` or `ACCEPTED_RISK` vulnerability is never overdue, however old the date is. The
`overdue` field of the response is computed, never stored, and uses the same instant for the whole page — the
filter and each row's flag are evaluated against the same "now".

`overdue=false` returns everything that is **not** overdue, including the vulnerabilities with no `dueDate`. That
is not automatic in SQL (`NULL < now` is UNKNOWN), which is why the negation is applied over the whole
conjunction in `VulnerabilitySpecifications`.

```http
GET /api/v1/vulnerabilities?severity=CRITICAL&status=OPEN&overdue=true&sort=cvssScore,desc&size=5
Authorization: Bearer {{accessToken}}
```

`200 OK`

```json
{
  "content": [
    {
      "id": 101,
      "title": "SQL injection no endpoint de busca",
      "description": "Parâmetro concatenado diretamente na query",
      "severity": "CRITICAL",
      "status": "OPEN",
      "cvssScore": 9.1,
      "cve": "CVE-2024-12345",
      "discoveredAt": "2026-08-30T09:00:00Z",
      "dueDate": "2026-09-14T00:00:00Z",
      "overdue": true,
      "assetId": 21,
      "assetName": "API de pagamentos",
      "projectId": 7,
      "projectName": "Portal do Cliente",
      "createdByName": "Bruno Carvalho",
      "createdAt": "2026-08-30T09:05:41Z",
      "updatedAt": "2026-08-30T09:05:41Z"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 1,
  "totalPages": 1,
  "sort": "cvssScore,desc"
}
```

This finding has not been resolved or assigned yet: **there is no `resolvedAt` key and no `assignedTo` key**.
`overdue`, being primitive, always appears.

The dashboard's "recent items" panel is this same listing with `?page=0&size=5&sort=createdAt,desc` — there is
no fifth dashboard endpoint for it.

### POST /vulnerabilities

Required: `assetId`, `title` (3 to 200 characters) and `severity`. Optional: `description` (up to 4000),
`cvssScore`, `cve`, `discoveredAt`, `dueDate`, `assignedToId`.

- `cvssScore`: a `BigDecimal` between `0.0` and `10.0`, with at most one decimal place.
- `cve`: accepts the pattern `CVE-YYYY-NNNN` (with four or more trailing digits) **or an empty string**. The
  empty string is accepted on purpose — an Angular reactive form sends `""` for an untouched optional field —
  and becomes `null`; the rest is normalised to upper case.
- An absent `discoveredAt` means "discovered now", not the zero epoch.
- **There is no `status` in the body.** Every vulnerability is born `OPEN`, and the status changes only through
  `PATCH /status`. Accepting `status` here would turn the `PUT` — which the matrix reserves to ADMIN and
  ANALYST — into a second, less guarded path for driving a finding to `RESOLVED`.

```http
POST /api/v1/vulnerabilities
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "assetId": 21,
  "title": "SQL injection no endpoint de busca",
  "description": "Parâmetro concatenado diretamente na query",
  "severity": "CRITICAL",
  "cvssScore": 9.1,
  "cve": "CVE-2024-12345",
  "discoveredAt": "2026-09-17T12:02:00Z",
  "dueDate": "2026-10-01T00:00:00Z"
}
```

`201 Created`

```json
{
  "id": 101,
  "title": "SQL injection no endpoint de busca",
  "description": "Parâmetro concatenado diretamente na query",
  "severity": "CRITICAL",
  "status": "OPEN",
  "cvssScore": 9.1,
  "cve": "CVE-2024-12345",
  "discoveredAt": "2026-09-17T12:02:00Z",
  "dueDate": "2026-10-01T00:00:00Z",
  "overdue": false,
  "assetId": 21,
  "assetName": "API de pagamentos",
  "projectId": 7,
  "projectName": "Portal do Cliente",
  "createdByName": "Bruno Carvalho",
  "createdAt": "2026-09-17T12:02:00Z",
  "updatedAt": "2026-09-17T12:02:00Z"
}
```

An `assetId` from another company: `404` ("Ativo 999 não encontrado"). An unknown `assignedToId`, one from
another company or one belonging to a deactivated user: `404` ("Usuário 999 não encontrado") — the three cases
are indistinguishable on purpose.

### PUT /vulnerabilities/{id}

Same body as the `POST`. It can move the finding to another asset of the same company and can change the
assignee through `assignedToId` (absent **unassigns**). It touches neither `status` nor `resolvedAt`.

### PATCH /vulnerabilities/{id}/status

```http
PATCH /api/v1/vulnerabilities/101/status
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{ "status": "IN_PROGRESS" }
```

`200 OK` with the complete vulnerability.

Rules:

- `status` is required; absent it answers `400` with `fieldErrors: [{ "field": "status", "message": "é obrigatório" }]`.
- There is no state machine: **any transition is accepted**, including `RESOLVED → OPEN`.
- Entering `RESOLVED` stamps `resolvedAt` with the current instant; leaving `RESOLVED` clears the stamp. The
  same equivalence is a `CHECK` constraint in migration V5, so a bug here cannot persist.
- Changing to the status the item already has is a no-op: it answers `200` and writes **no** audit row.

Response after `{"status": "RESOLVED"}` — the `resolvedAt` key now exists and `overdue` is back to `false`:

```json
{
  "id": 101,
  "title": "SQL injection no endpoint de busca",
  "severity": "CRITICAL",
  "status": "RESOLVED",
  "cvssScore": 9.1,
  "cve": "CVE-2024-12345",
  "discoveredAt": "2026-09-17T12:02:00Z",
  "dueDate": "2026-10-01T00:00:00Z",
  "resolvedAt": "2026-09-17T12:06:30Z",
  "overdue": false,
  "assetId": 21,
  "assetName": "API de pagamentos",
  "projectId": 7,
  "projectName": "Portal do Cliente",
  "assignedTo": {
    "id": 3,
    "name": "Carla Mendes",
    "email": "developer@demo.test",
    "role": "DEVELOPER"
  },
  "createdByName": "Bruno Carvalho",
  "createdAt": "2026-09-17T12:02:00Z",
  "updatedAt": "2026-09-17T12:06:30Z"
}
```

### PATCH /vulnerabilities/{id}/assignee

```http
PATCH /api/v1/vulnerabilities/101/assignee
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{ "userId": 3 }
```

`200 OK` with the vulnerability, now carrying the `assignedTo` block (a `UserSummary`: `id`, `name`, `email`,
`role`).

`{"userId": null}` is a valid request and **unassigns** the item — `userId` is not `@NotNull` for exactly that
reason. After that the `assignedTo` key disappears from the response.

The assignee has to be an **active** user of the same company; every other case is a `404`.

### DELETE /vulnerabilities/{id}

`204 No Content`. Unlike projects and assets, **the comments are removed along with it** instead of blocking the
deletion: there is no endpoint that deletes a comment (§6), so a conflict here would make every commented
vulnerability permanently undeletable. How many comments were removed goes into the audit trail (`commentCount`
in the `oldValue`), which is what keeps the deletion accountable.

---

## Comments

Nested under the vulnerability, because a comment has no meaning of its own. **There is no delete endpoint** in
the MVP.

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/vulnerabilities/{vulnerabilityId}/comments` | any authenticated role |
| POST | `/vulnerabilities/{vulnerabilityId}/comments` | `ADMIN`, `ANALYST`, `DEVELOPER` |
| PUT | `/vulnerabilities/{vulnerabilityId}/comments/{commentId}` | the comment's author **or** `ADMIN` |

In any of the three operations, the parent vulnerability is loaded and validated against the caller's company
first: a parent from another tenant is a `404` before anything can observe that the comment exists.

### GET .../comments

Accepts `page`, `size` and `sort` (the only sortable property is `createdAt`; default `createdAt,asc;id,asc`).

```http
GET /api/v1/vulnerabilities/101/comments?page=0&size=20
Authorization: Bearer {{accessToken}}
```

`200 OK`

```json
{
  "content": [
    {
      "id": 55,
      "vulnerabilityId": 101,
      "content": "Correção iniciada; o parâmetro passa a usar bind.",
      "author": {
        "id": 3,
        "name": "Carla Mendes",
        "email": "developer@demo.test",
        "role": "DEVELOPER"
      },
      "editable": true,
      "createdAt": "2026-09-17T12:05:10Z",
      "updatedAt": "2026-09-17T12:05:10Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "sort": "createdAt,asc;id,asc"
}
```

`editable` mirrors on the server the rule the server will reapply on the edit (author or ADMIN) — the UI uses
that field to decide whether to show the "edit" button, instead of deducing the rule on its own. It varies with
who is reading: the same comment comes with `editable: true` for the author and `editable: false` for an
`ANALYST` colleague.

### POST .../comments

The body carries only the text: the vulnerability comes from the path, the author and the company come from the
principal. `content` is required and goes up to 2000 characters.

```http
POST /api/v1/vulnerabilities/101/comments
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{ "content": "Correção iniciada; o parâmetro passa a usar bind." }
```

`201 Created` with the comment object above.

A `VIEWER` gets `403 FORBIDDEN` ("Acesso negado"): the role is strictly read-only.

### PUT .../comments/{commentId}

Same body as the `POST`, `200 OK` in the response. Anyone who is neither the author nor an `ADMIN` gets
`403 FORBIDDEN` with "Apenas o autor ou um administrador pode editar o comentário".

The order matters: the parent and the comment are loaded **before** the authorship check, so anything outside
the caller's company is a `404` and the `403` can only mean "this exists here, but it is not yours".

**The audit trail does not store the comment text**, only `contentLength`. `AuditSanitizer` masks by key name,
not by value, so a credential pasted into a comment would land readable in every ADMIN's trail. Nothing is lost:
with no physical deletion, the text is always available from the endpoint itself.

---

## Users

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/users` | `ADMIN`, `ANALYST` |

`GET /users?role=&active=&search=`

| Parameter | Type | Effect |
| --- | --- | --- |
| `role` | `Role` | exact equality |
| `active` | `boolean` | exact equality |
| `search` | text | case-insensitive `contains` on `name` **or** `email` |

No pagination and no `sort`: always ordered by `name` ascending, always limited to the caller's company.
`ANALYST` is included because assigning a vulnerability requires choosing a user.

```http
GET /api/v1/users?active=true&role=DEVELOPER
Authorization: Bearer {{accessToken}}
```

`200 OK` — plain array, no envelope:

```json
[
  {
    "id": 3,
    "name": "Carla Mendes",
    "email": "developer@demo.test",
    "role": "DEVELOPER",
    "active": true,
    "companyId": 1,
    "companyName": "Demo Security",
    "lastLoginAt": "2026-09-17T11:58:20Z",
    "createdAt": "2026-09-01T09:14:22Z"
  }
]
```

A user who has never signed in does not carry the `lastLoginAt` key.

---

## Audit trail

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/audit-logs` | `ADMIN` |

The trail is read-only through the API: there is no endpoint that changes or deletes an entry.

`GET /audit-logs?page=&size=&sort=&entityType=&actorId=&action=&from=&to=`

| Parameter | Type | Effect |
| --- | --- | --- |
| `entityType` | text | exact equality; values in use: `Company`, `User`, `Project`, `Asset`, `Vulnerability`, `Comment` |
| `actorId` | `Long` | id of whoever acted |
| `action` | `AuditAction` | exact equality |
| `from` | ISO-8601 | `createdAt >= from` |
| `to` | ISO-8601 | `createdAt <= to` |
| `page`, `size`, `sort` | — | sortable: `createdAt`, `action`, `entityType`; default `createdAt,desc` |

```http
GET /api/v1/audit-logs?entityType=Vulnerability&action=STATUS_CHANGE&from=2026-09-17T00:00:00Z&size=20
Authorization: Bearer {{accessToken}}
```

`200 OK`

```json
{
  "content": [
    {
      "id": 480,
      "actorId": 3,
      "actorEmail": "developer@demo.test",
      "action": "STATUS_CHANGE",
      "entityType": "Vulnerability",
      "entityId": 101,
      "oldValue": { "status": "IN_PROGRESS" },
      "newValue": { "status": "RESOLVED", "resolvedAt": "2026-09-17T12:06:30Z" },
      "ipAddress": "172.18.0.1",
      "createdAt": "2026-09-17T12:06:30Z"
    },
    {
      "id": 479,
      "actorId": 2,
      "actorEmail": "analyst@demo.test",
      "action": "CREATE",
      "entityType": "Vulnerability",
      "entityId": 101,
      "newValue": {
        "title": "SQL injection no endpoint de busca",
        "severity": "CRITICAL",
        "cvssScore": 9.1,
        "cve": "CVE-2024-12345",
        "status": "OPEN",
        "discoveredAt": "2026-09-17T12:02:00Z",
        "dueDate": "2026-10-01T00:00:00Z",
        "assetId": 21
      },
      "ipAddress": "172.18.0.1",
      "createdAt": "2026-09-17T12:02:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "sort": "createdAt,desc"
}
```

Details worth knowing:

- `oldValue` and `newValue` are free-form maps, stored as text so that the trail preserves the shape the entity
  had at the time. A `CREATE` has no `oldValue`, a `DELETE` has no `newValue` — and, by the `non_null` rule, the
  corresponding key disappears.
- The `snapshot` keys vary by action on purpose: a `STATUS_CHANGE` records only `status` and `resolvedAt`, an
  `ASSIGN` records only `assignedToId` and `assignedToEmail`, a `CREATE`/`UPDATE` records the whole row. A
  status change says what changed, not the whole entity again.
- Fields whose **name** suggests a credential (`password`, `senha`, `hash`, `token`, `secret`, `credential`,
  `authorization`, `apikey`, `otp`, `cvv`, ...) are replaced by `"***"` before serialising.
- `LOGIN_FAILED` is written in its own transaction, so the attempt is recorded even though the request ends in a
  `401`.
- `ipAddress` respects `X-Forwarded-For` when it is present.

---

## Dashboard

Four endpoints; they all require only authentication, because the permission matrix gives "view dashboard" to
all four roles. All of them scoped by the company in the token.

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/dashboard/summary` | any authenticated role |
| GET | `/dashboard/severity-distribution` | any authenticated role |
| GET | `/dashboard/status-distribution` | any authenticated role |
| GET | `/dashboard/trend?days=30` | any authenticated role |

### GET /dashboard/summary

```json
{
  "totalVulnerabilities": 42,
  "openVulnerabilities": 25,
  "criticalOpenVulnerabilities": 6,
  "overdueVulnerabilities": 4,
  "resolvedVulnerabilities": 14,
  "totalProjects": 3,
  "totalAssets": 11,
  "topProjects": [
    { "projectId": 7, "projectName": "Portal do Cliente", "total": 22, "open": 15, "overdue": 3 },
    { "projectId": 8, "projectName": "App Mobile", "total": 14, "open": 8, "overdue": 1 }
  ]
}
```

- `openVulnerabilities` is **not** a status count: it is the "still actionable" bucket, `OPEN + IN_PROGRESS`.
  The per-status breakdown belongs to `/status-distribution` — two owners for the same number is how they end up
  disagreeing.
- `overdueVulnerabilities` uses exactly the predicate of `GET /vulnerabilities?overdue=true`.
- `topProjects` returns at most ten rows, ordered by `total` desc and then `projectName` asc; projects with no
  findings do not appear. A company with no vulnerabilities returns `topProjects: []`.
- Every counter is primitive and appears even when it is `0`.

### GET /dashboard/severity-distribution

A plain array with the four severities **always present**, in the enum's declaration order, including the ones
that are zero — a chart legend that gains and loses entries (and shuffles the colours) between two reloads is
worse than one with visible zeros.

```json
[
  { "severity": "LOW", "count": 9 },
  { "severity": "MEDIUM", "count": 14 },
  { "severity": "HIGH", "count": 13 },
  { "severity": "CRITICAL", "count": 6 }
]
```

### GET /dashboard/status-distribution

Same shape, with the four statuses:

```json
[
  { "status": "OPEN", "count": 18 },
  { "status": "IN_PROGRESS", "count": 7 },
  { "status": "RESOLVED", "count": 14 },
  { "status": "ACCEPTED_RISK", "count": 3 }
]
```

### GET /dashboard/trend

`days` is optional and defaults to `30`. The value is **silently clamped** to the range `[1, 90]` — a chart
control cannot open an error box — and the response echoes the window actually used, which is what keeps that
clamp visible instead of hidden. `days=365` answers `200` with `days: 90`.

The window is `[today - (days - 1), today]` in UTC, both inclusive, so `days=30` returns exactly 30 points, from
oldest to newest, with empty days present and zeroed.

`opened` counts by `discoveredAt`, `resolved` by `resolvedAt`. Neither series uses `createdAt`: in a seeded or
imported database every row shares a `createdAt` and the trend would become a single spike that says nothing
about the backlog.

```http
GET /api/v1/dashboard/trend?days=7
Authorization: Bearer {{accessToken}}
```

```json
{
  "days": 7,
  "from": "2026-09-11",
  "to": "2026-09-17",
  "points": [
    { "date": "2026-09-11", "opened": 2, "resolved": 0 },
    { "date": "2026-09-12", "opened": 0, "resolved": 1 },
    { "date": "2026-09-13", "opened": 0, "resolved": 0 },
    { "date": "2026-09-14", "opened": 3, "resolved": 1 },
    { "date": "2026-09-15", "opened": 1, "resolved": 2 },
    { "date": "2026-09-16", "opened": 0, "resolved": 0 },
    { "date": "2026-09-17", "opened": 1, "resolved": 1 }
  ]
}
```

---

## Scan import

| Method | Endpoint | Access |
| --- | --- | --- |
| POST | `/scan-imports` | `ADMIN`, `ANALYST` |
| GET | `/scan-imports/{id}` | any authenticated role |
| PATCH | `/scan-imports/{id}/findings/{findingId}` | `ADMIN`, `ANALYST` |
| POST | `/scan-imports/{id}/confirm` | `ADMIN`, `ANALYST` |
| DELETE | `/scan-imports/{id}` | `ADMIN`, `ANALYST` |
| GET | `/scan-imports` | any authenticated role |

The flow is **upload, review, then confirm or discard**. The upload creates nothing: it reads the report, looks
for an asset of the chosen project for each finding, marks the findings the company has already recorded and
stores all of that as a proposal. Only the confirmation creates vulnerabilities, and only for the findings that
still have an asset at that moment.

Three rules explain almost everything else:

- **No asset is created.** A finding's target is compared with the `identifier` of the assets **of the chosen
  project**, case-insensitively and ignoring surrounding whitespace. With no match, the finding stays
  `UNMATCHED` and waits for someone to say which asset it is — inventing an asset from a hostname would fill
  the inventory with ownerless rows.
- **A duplicate is skipped and counted, never merged.** A finding's fingerprint is
  `sha256(scanner:ruleId:target:cve)` — severity and CVSS are left out on purpose, because they change between
  scanner versions without the finding being a different one. A finding whose fingerprint the company already
  carries becomes `DUPLICATE` and is skipped at confirmation: updating or reopening the existing vulnerability
  would silently undo the status, the assignee and the discussion someone put there.
- **Import is synchronous, with a ceiling.** `securityhub.scan.max-findings` (default 2000) limits how many
  findings a file can stage; above that the upload is refused with a 400 **before anything is written**, and
  there is no status endpoint to poll afterwards.

What each format reads:

| `format` | Source | What becomes a finding | Target |
| --- | --- | --- | --- |
| `NMAP_XML` | `nmap -oX` | **NSE script results only.** An open port is not a vulnerability, and neither is a service banner | the host's hostname when nmap resolved one, otherwise the address (never the MAC) |
| `ZAP_JSON` | OWASP ZAP JSON report | one finding per **instance** of each alert; an alert with no instances keeps the site | the instance's `uri`, in full |
| `NUCLEI_JSONL` | `nuclei -jsonl` | one finding per line; a line that is not JSON is skipped and the rest of the file continues | `matched-at`, with `host` as a fallback |

The format is **declared by the uploader** and never inferred from the bytes: all three are UTF-8 text, and
guessing would get "this is XML" right without getting "this is an nmap report" right.

### POST /scan-imports

`multipart/form-data` with three parts: `file`, `projectId` and `format`. `projectId` and `format` are read as
request parameters, so they also work in the query string.

```http
POST /api/v1/scan-imports
Authorization: Bearer {{accessToken}}
Content-Type: multipart/form-data; boundary=----exemplo

------exemplo
Content-Disposition: form-data; name="projectId"

7
------exemplo
Content-Disposition: form-data; name="format"

NMAP_XML
------exemplo
Content-Disposition: form-data; name="file"; filename="varredura-portal.xml"
Content-Type: application/xml

<?xml version="1.0"?><nmaprun>…</nmaprun>
------exemplo--
```

`201 Created` with the import **and all of its findings** — the client has just uploaded the file and needs to
show the preview, not make a second call to fetch it:

```json
{
  "id": 12,
  "projectId": 7,
  "projectName": "Portal do Cliente",
  "format": "NMAP_XML",
  "originalFilename": "varredura-portal.xml",
  "sizeBytes": 18432,
  "status": "PENDING",
  "totalFindings": 3,
  "matchedCount": 1,
  "unmatchedCount": 1,
  "duplicateCount": 1,
  "importedCount": 0,
  "skippedCount": 0,
  "importedByName": "Bruno Carvalho",
  "createdAt": "2026-09-17T12:00:00Z",
  "updatedAt": "2026-09-17T12:00:00Z",
  "findings": [
    {
      "id": 41,
      "ruleId": "ssl-heartbleed",
      "title": "ssl-heartbleed",
      "description": "VULNERABLE: The Heartbleed Bug is a serious vulnerability in OpenSSL.",
      "severity": "HIGH",
      "cve": "CVE-2014-0160",
      "target": "portal.demo.test",
      "discoveredAt": "2023-11-14T22:13:20Z",
      "status": "MATCHED",
      "assetId": 4,
      "assetName": "Portal do Cliente"
    },
    {
      "id": 42,
      "ruleId": "smb-vuln-ms17-010",
      "title": "smb-vuln-ms17-010",
      "description": "VULNERABLE: Remote Code Execution vulnerability in Microsoft SMBv1 servers.",
      "severity": "HIGH",
      "cve": "CVE-2017-0143",
      "target": "10.0.0.11",
      "discoveredAt": "2023-11-14T22:13:20Z",
      "status": "UNMATCHED"
    },
    {
      "id": 43,
      "ruleId": "http-csrf",
      "title": "http-csrf",
      "severity": "MEDIUM",
      "target": "portal.demo.test",
      "discoveredAt": "2023-11-14T22:13:20Z",
      "status": "DUPLICATE",
      "assetId": 4,
      "assetName": "Portal do Cliente"
    }
  ]
}
```

Notice what is **not** there: finding 42 has no `assetId` and no `assetName` (the `non_null` rule removes the
key), no finding has a `vulnerabilityId` while the import is pending, and finding 41 has no `cvssScore` because
nmap does not give a score — its severity is derived from the script text (citing a CVE or the word `VULNERABLE`
is `HIGH`, the rest is `MEDIUM`; `CRITICAL` is never invented). The file name on disk is never exposed.

Finding 43 arrived as `DUPLICATE` even though it found the asset: duplicate wins over "matched", because a
duplicate with an asset is still something the company has already recorded.

Specific errors:

- An empty file, or one unreadable by the parser for the chosen format: `400 BAD_REQUEST` — "O relatório nmap
  enviado não é um XML válido".
- Above `securityhub.scan.max-findings`: `400 BAD_REQUEST` — "O relatório contém 5120 achados e o limite por
  importação é 2000; filtre o relatório no scanner (por severidade ou por host) ou divida-o em arquivos menores
  e envie um de cada vez". Nothing is written and no file stays on disk.
- Above `securityhub.scan.max-upload-bytes` (default 10 MiB): `413 PAYLOAD_TOO_LARGE`.
- A `format` outside the enum: `400 BAD_REQUEST`, during deserialisation, before the service runs.
- A `projectId` from another company or a non-existent one: `404 NOT_FOUND` — "Projeto 999 não encontrado".
- The `DEVELOPER` or `VIEWER` role: `403 FORBIDDEN`.

### GET /scan-imports/{id}

The preview: the same structure as the upload, with the findings in the state they are in now. The findings are
not paginated — their number is already capped by `max-findings`, and paginating the review screen would ask the
operator to map twenty at a time.

```http
GET /api/v1/scan-imports/12
Authorization: Bearer {{accessToken}}
```

Open to any role in the company. An import from another company is a `404`, never a `403`.

### PATCH /scan-imports/{id}/findings/{findingId}

Gives an `UNMATCHED` finding the asset its target did not resolve on its own.

```http
PATCH /api/v1/scan-imports/12/findings/42
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "assetId": 9
}
```

`200 OK` with the updated finding — and only it, because it is the only row that changed state:

```json
{
  "id": 42,
  "ruleId": "smb-vuln-ms17-010",
  "title": "smb-vuln-ms17-010",
  "description": "VULNERABLE: Remote Code Execution vulnerability in Microsoft SMBv1 servers.",
  "severity": "HIGH",
  "cve": "CVE-2017-0143",
  "target": "10.0.0.11",
  "discoveredAt": "2023-11-14T22:13:20Z",
  "status": "MATCHED",
  "assetId": 9,
  "assetName": "Gateway de Borda"
}
```

The import counters are recomputed in the same transaction, so the next `GET` already carries a larger
`matchedCount` and a smaller `unmatchedCount`.

The asset has to belong to **the company**, and not necessarily to the import's project: a scan that reported
`10.0.0.11` may have hit an asset registered in another project of the same tenant, and refusing that would
leave the operator with a finding they know the owner of and cannot import. (The screen offers only the assets
of the import's project, which is the common case; the API accepts the others.)

Specific errors:

- A finding that is not `UNMATCHED`: `409 CONFLICT` — "Somente um achado sem ativo pode ser mapeado; este está
  MATCHED". A finding that is already resolved has nothing to change, and a `DUPLICATE` would not be imported
  anyway.
- An import that is already confirmed or discarded: `409 CONFLICT`.
- An `assetId` from another company or a non-existent one: `404 NOT_FOUND` — "Ativo 999 não encontrado".
- A `findingId` that does not belong to this import: `404 NOT_FOUND`.

### POST /scan-imports/{id}/confirm

Creates one vulnerability per `MATCHED` finding, in a single batch, and closes the import. No body.

```http
POST /api/v1/scan-imports/12/confirm
Authorization: Bearer {{accessToken}}
```

`200 OK` with the import already in `CONFIRMED`:

```json
{
  "id": 12,
  "projectId": 7,
  "projectName": "Portal do Cliente",
  "format": "NMAP_XML",
  "originalFilename": "varredura-portal.xml",
  "sizeBytes": 18432,
  "status": "CONFIRMED",
  "totalFindings": 3,
  "matchedCount": 0,
  "unmatchedCount": 0,
  "duplicateCount": 0,
  "importedCount": 2,
  "skippedCount": 1,
  "importedByName": "Bruno Carvalho",
  "createdAt": "2026-09-17T12:00:00Z",
  "updatedAt": "2026-09-17T12:07:41Z",
  "findings": [
    {
      "id": 41,
      "ruleId": "ssl-heartbleed",
      "title": "ssl-heartbleed",
      "severity": "HIGH",
      "cve": "CVE-2014-0160",
      "target": "portal.demo.test",
      "discoveredAt": "2023-11-14T22:13:20Z",
      "status": "IMPORTED",
      "assetId": 4,
      "assetName": "Portal do Cliente",
      "vulnerabilityId": 87
    },
    {
      "id": 42,
      "ruleId": "smb-vuln-ms17-010",
      "title": "smb-vuln-ms17-010",
      "severity": "HIGH",
      "cve": "CVE-2017-0143",
      "target": "10.0.0.11",
      "discoveredAt": "2023-11-14T22:13:20Z",
      "status": "IMPORTED",
      "assetId": 9,
      "assetName": "Gateway de Borda",
      "vulnerabilityId": 88
    },
    {
      "id": 43,
      "ruleId": "http-csrf",
      "title": "http-csrf",
      "severity": "MEDIUM",
      "target": "portal.demo.test",
      "discoveredAt": "2023-11-14T22:13:20Z",
      "status": "SKIPPED",
      "assetId": 4,
      "assetName": "Portal do Cliente"
    }
  ]
}
```

**The counters mean something different after the confirmation**: the five statuses are mutually exclusive, so
`matchedCount` drops to zero and what was `MATCHED` appears in `importedCount`. Everything that was not
`MATCHED` — what nobody mapped and what the company already had — becomes `SKIPPED`.

Duplication is checked **again** here, not reused from the upload: an import staged yesterday may be confirmed
after another one has already created the same finding. That is why a finding that was `MATCHED` in the preview
can end up `SKIPPED`.

Every vulnerability created is born `OPEN`, with no assignee, with `createdBy` set to whoever confirmed, and it
carries the finding's fingerprint — that is what makes the next import of the same report create nothing.

In the audit trail this appears as **one** `SCAN_IMPORT` entry about `ScanImport`, carrying the counters, the
project, the format and the file name. There is no `CREATE` per vulnerability: hundreds of identical rows would
bury the trail, and per-finding traceability lives in `scan_findings.vulnerability_id`, which the preview
returns as `vulnerabilityId`.

Specific errors:

- An import that is not `PENDING`: `409 CONFLICT` — "Não é possível confirmar uma importação com status
  CONFIRMED; apenas importações pendentes podem ser alteradas". A second confirmation is **not** an idempotent
  success: the first one created rows, and answering 200 would tell a client that repeated the call that it
  created rows too.

### DELETE /scan-imports/{id}

Discards a pending import. `204 No Content`.

```http
DELETE /api/v1/scan-imports/12
Authorization: Bearer {{accessToken}}
```

Despite the verb, **nothing is deleted from the history**: the import moves to `DISCARDED` and stays listed,
with the findings the report brought. What goes is the file on disk, removed after the commit — the counters of
a discarded import are how someone answers, months later, "yes, we scanned that host, and we chose not to
import".

A confirmed import answers `409 CONFLICT`: its file backs vulnerabilities that exist, and it is the only one in
this feature that has earned the right to stay.

### GET /scan-imports

The company's paginated history, newest first. **Without the findings** — a page of twenty imports carrying all
the findings of each one would be thousands of rows to draw six numbers.

```http
GET /api/v1/scan-imports?page=0&size=20&sort=createdAt,desc
Authorization: Bearer {{accessToken}}
```

```json
{
  "content": [
    {
      "id": 12,
      "projectId": 7,
      "projectName": "Portal do Cliente",
      "format": "NMAP_XML",
      "originalFilename": "varredura-portal.xml",
      "sizeBytes": 18432,
      "status": "CONFIRMED",
      "totalFindings": 3,
      "matchedCount": 0,
      "unmatchedCount": 0,
      "duplicateCount": 0,
      "importedCount": 2,
      "skippedCount": 1,
      "importedByName": "Bruno Carvalho",
      "createdAt": "2026-09-17T12:00:00Z",
      "updatedAt": "2026-09-17T12:07:41Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "sort": "createdAt,desc"
}
```

`importedByName` is the name of whoever imported, never the id or the e-mail. The six counters are primitive and
therefore always appear, including when they are zero.

---

## End-to-end walkthrough

This is the same flow `scripts/smoke-test.sh` runs against a running stack, and the same one
`docs/http/securityhub.http` runs from top to bottom. The ids are the example's; substitute your own.

### 1. Company registration

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "companyName": "Acme Segurança",
  "name": "Administrador",
  "email": "admin@acme.test",
  "password": "uma-senha-suficientemente-longa"
}
```

`201` — the first user is always `ADMIN`. Keep `user.id` (here: `12`); it will be the assignee in step 5.

### 2. Login

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "email": "admin@acme.test", "password": "uma-senha-suficientemente-longa" }
```

`200` — keep the `accessToken`. Every following call carries `Authorization: Bearer <accessToken>`.

### 3. Create the project

```http
POST /api/v1/projects
{ "name": "Projeto Acme", "description": "Criado no passo a passo" }
```

`201` → `id: 7`. With no `status` in the body, the project is born `ACTIVE` with `assetCount: 0`.

### 4. Create the asset

```http
POST /api/v1/assets
{
  "projectId": 7,
  "name": "API de pagamentos",
  "type": "API",
  "identifier": "api.acme.test",
  "environment": "PRODUCTION",
  "criticality": "HIGH"
}
```

`201` → `id: 21`, `vulnerabilityCount: 0`.

### 5. Create the vulnerability

```http
POST /api/v1/vulnerabilities
{
  "assetId": 21,
  "title": "SQL injection no endpoint de busca",
  "description": "Parâmetro concatenado diretamente na query",
  "severity": "CRITICAL",
  "cvssScore": 9.1,
  "cve": "CVE-2024-12345",
  "discoveredAt": "2026-09-17T12:02:00Z"
}
```

`201` → `id: 101`, `status: "OPEN"`, with no `resolvedAt` and no `assignedTo`.

### 6. Assign

```http
PATCH /api/v1/vulnerabilities/101/assignee
{ "userId": 12 }
```

`200` — the response gains the `assignedTo` block with `id`, `name`, `email` and `role`.

### 7. Move to in progress

```http
PATCH /api/v1/vulnerabilities/101/status
{ "status": "IN_PROGRESS" }
```

`200` — `status: "IN_PROGRESS"`; still no `resolvedAt`.

### 8. Comment

```http
POST /api/v1/vulnerabilities/101/comments
{ "content": "Correção iniciada; o parâmetro passa a usar bind." }
```

`201` → `id: 55`, with `author` and `editable: true` (you are the author).

### 9. Resolve

```http
PATCH /api/v1/vulnerabilities/101/status
{ "status": "RESOLVED" }
```

`200` — the `resolvedAt` key now **appears**, stamped with the instant of the transition, and `overdue` is
`false`.

### 10. Read the audit trail

```http
GET /api/v1/audit-logs?size=50
```

`200` — the trail already contains, from newest to oldest: `STATUS_CHANGE` (to `RESOLVED`), `COMMENT`,
`STATUS_CHANGE` (to `IN_PROGRESS`), `ASSIGN`, `CREATE` (Vulnerability), `CREATE` (Asset), `CREATE` (Project),
`LOGIN` and `REGISTER`. No sensitive field appears in the clear.

### 11. Read the dashboard

```http
GET /api/v1/dashboard/summary
```

`200` — `totalProjects: 1`, `totalAssets: 1`, `totalVulnerabilities: 1`, `resolvedVulnerabilities: 1`,
`openVulnerabilities: 0`, and `topProjects` with one row for "Projeto Acme".

The other three dashboard endpoints (`severity-distribution`, `status-distribution`, `trend?days=30`) answer
from the same data.

---

## Errors that matter

### 401 — no token, or an invalid/expired one

Any endpoint outside the public list. Response from `RestAuthenticationEntryPoint`:

```http
GET /api/v1/projects
```

`401 Unauthorized`

```json
{
  "timestamp": "2026-09-17T12:10:00Z",
  "status": 401,
  "code": "UNAUTHORIZED",
  "message": "Autenticação necessária",
  "path": "/api/v1/projects",
  "traceId": "7f3a1c9e4b2d5a68"
}
```

A token signed with another secret, an expired one or one from a deactivated user gives exactly the same body —
the response does not say which case occurred. The "Credenciais inválidas" message (also `401`) appears only on
`POST /auth/login`.

### 403 — authenticated, but the role does not have the permission

A `DEVELOPER` trying to create a project (the matrix reserves projects to `ADMIN`):

```http
POST /api/v1/projects
Authorization: Bearer <token de DEVELOPER>

{ "name": "Projeto novo" }
```

`403 Forbidden`

```json
{
  "timestamp": "2026-09-17T12:11:00Z",
  "status": 403,
  "code": "FORBIDDEN",
  "message": "Acesso negado",
  "path": "/api/v1/projects",
  "traceId": "3d9e1f07ac42b5c1"
}
```

The same response for a `VIEWER` commenting, an `ANALYST` deleting a vulnerability, or any role other than
`ADMIN` querying `/audit-logs`. The check lives in the **service**, not only in the controller, so a caller that
does not go through HTTP (a scheduler, an importer) runs into it too.

### 404 — a resource from another company

```http
GET /api/v1/projects/7
Authorization: Bearer <token of company B; project 7 belongs to company A>
```

`404 Not Found`

```json
{
  "timestamp": "2026-09-17T12:12:00Z",
  "status": 404,
  "code": "NOT_FOUND",
  "message": "Projeto 7 não encontrado",
  "path": "/api/v1/projects/7",
  "traceId": "b41c07de9a2f6538"
}
```

**Why 404 and not 403.** A `403` would answer "this exists, but it is not yours" — and that is exactly the
information an attacker wants. By sweeping `/projects/1`, `/projects/2`, ... they would map which ids are taken
across the whole database, how many projects the system has and at what rate they are created, without ever
seeing a single piece of data. The API would become an enumeration oracle. With a `404`, a resource from another
company is **indistinguishable from one that does not exist**, and the sweep returns no bits.

That is why the rule is uniform: `require(...)` loads the row filtering by `companyId` and throws `404`; no
domain service throws `403` for a tenant. It holds for `GET`, `PUT`, `PATCH` and `DELETE`, and also for
references in the body — a `projectId`, `assetId` or `assignedToId` from another company is a `404`, not a
`403`.

Intended consequence: the `403` is reserved for "you are in the right place, but you cannot do this" — a role
without permission, someone else's comment, a finding assigned to someone else. A `403` never confirms the
existence of anything outside your company.

### 409 — duplicate name

```http
POST /api/v1/projects
{ "name": "Portal do Cliente" }
```

`409 Conflict`

```json
{
  "timestamp": "2026-09-17T12:13:00Z",
  "status": 409,
  "code": "CONFLICT",
  "message": "Já existe um projeto com esse nome nesta empresa",
  "path": "/api/v1/projects",
  "traceId": "c8f50a1b7e3d9426"
}
```

Variants: "Já existe um ativo com esse identificador neste projeto" (`POST`/`PUT /assets`) and "E-mail já
cadastrado" (`POST /auth/register`).

### 409 — deleting a parent that still has children

```http
DELETE /api/v1/projects/7
```

`409 Conflict`

```json
{
  "timestamp": "2026-09-17T12:14:00Z",
  "status": 409,
  "code": "CONFLICT",
  "message": "O projeto possui 3 ativo(s) e não pode ser excluído",
  "path": "/api/v1/projects/7",
  "traceId": "2a6b91c4f0e78d33"
}
```

And the equivalent for assets: "O ativo possui 4 vulnerabilidade(s) e não pode ser excluído". A vulnerability is
the deliberate exception to the rule — its comments are deleted along with it, because there is no endpoint that
deletes a comment on its own.

### 400 — validation with `fieldErrors`

```http
POST /api/v1/vulnerabilities
{ "title": "ab", "cvssScore": 12.5 }
```

`400 Bad Request`

```json
{
  "timestamp": "2026-09-17T12:15:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Dados inválidos",
  "path": "/api/v1/vulnerabilities",
  "fieldErrors": [
    { "field": "assetId", "message": "é obrigatório" },
    { "field": "title", "message": "deve ter entre 3 e 200 caracteres" },
    { "field": "severity", "message": "é obrigatória" },
    { "field": "cvssScore", "message": "deve estar entre 0.0 e 10.0" }
  ],
  "traceId": "9e24d7b3c1a80f65"
}
```

Every invalid field comes back at once; the order of the array is not guaranteed. `message` is always "Dados
inválidos" — the actionable information is in `fieldErrors`.

Do not confuse it with the other `400`: malformed JSON, an invalid enum (`"severity": "URGENTE"`) or an
incompatible type in the query string (`?projectId=abc`) answer `code: "BAD_REQUEST"`,
`message: "Requisição malformada"` and **no** `fieldErrors`, because the failure happens during
deserialisation, before validation runs.

---

## The DEVELOPER ownership rule

The permission matrix gives the `DEVELOPER` a single write permission over vulnerabilities: **changing the
status of an item assigned to them** (besides commenting). They do not create, edit, delete or assign.

The rule does not fit in an annotation, because it depends on the row:
`@PreAuthorize("hasAnyRole('ADMIN','ANALYST','DEVELOPER')")` is only the coarse gate that lets the `DEVELOPER`
into `PATCH /status`; the fine check runs in the method body, after loading the vulnerability.

How that looks — a `DEVELOPER` trying to touch a finding assigned to someone else (or to nobody):

```http
PATCH /api/v1/vulnerabilities/101/status
Authorization: Bearer <token de DEVELOPER>
Content-Type: application/json

{ "status": "RESOLVED" }
```

`403 Forbidden`

```json
{
  "timestamp": "2026-09-17T12:16:00Z",
  "status": 403,
  "code": "FORBIDDEN",
  "message": "Você só pode alterar o status de vulnerabilidades atribuídas a você",
  "path": "/api/v1/vulnerabilities/101/status",
  "traceId": "5c17e9a2d34b608f"
}
```

Note that the message is **specific**, unlike the generic "Acesso negado" of the role `403`. That is safe
precisely because the order of the checks guarantees that this `403` can only occur inside your own company: the
vulnerability is loaded with `findByIdAndCompanyId` **before** the ownership rule, so an id from another tenant
has already come out as a `404`. If the check were done with `@PostAuthorize`, the `403` would appear for items
from another company too and would leak their existence.

Fine points:

- An **unassigned** item is not "nobody's, so everybody's": it is nobody's, and the `DEVELOPER` gets the same
  `403`.
- `ADMIN` and `ANALYST` go straight through, with no ownership check.
- A `VIEWER` never gets here: the `@PreAuthorize` blocks them first, with the generic "Acesso negado".
- Being assigned does **not** give the `DEVELOPER` access to `PUT /vulnerabilities/{id}` or to
  `PATCH /assignee` — those stay `403` for them. That is why `status` does not exist in the `PUT` body: if it
  did, `PUT` would be a second path to resolving a finding, with a different guard.
