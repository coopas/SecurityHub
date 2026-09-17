# Exemplos da API

Referência prática de toda a API do SecurityHub, derivada do código (`backend/src/main/java/com/securityhub`).
Cada endpoint traz método, caminho, quem pode chamar (matriz de `docs/permissions.md`), corpo da requisição quando houver
e uma resposta realista.

Para executar as chamadas: `docs/http/securityhub.http` (REST Client / IntelliJ) e
`docs/http/SecurityHub.postman_collection.json` (Postman). O contrato formal e navegável está no Swagger UI em
`http://localhost:8080/swagger-ui.html`.

---

## Convenções

| Item | Valor |
| --- | --- |
| Prefixo | `/api/v1` (base local: `http://localhost:8080/api/v1`) |
| Formato | JSON em camelCase, `Content-Type: application/json` |
| Autenticação | `Authorization: Bearer <accessToken>` |
| Datas | ISO-8601 em UTC (`2026-09-17T12:00:00Z`); `trend` usa datas civis `YYYY-MM-DD` |
| Tenant | Nunca vai no corpo nem na query. Sai sempre do JWT (`companyId`) |

### Campos nulos não aparecem

`spring.jackson.default-property-inclusion: non_null` (ver `application.yml`). Um campo nulo é **omitido** do
payload, não serializado como `null`. Uma vulnerabilidade não resolvida simplesmente **não tem a chave
`resolvedAt`**; um ativo sem `identifier` não tem a chave `identifier`; o `refreshToken` do login (V2, ainda
não implementado) não aparece na resposta.

A recíproca vale para campos primitivos: `overdue`, `editable`, `active`, `assetCount`, `vulnerabilityCount`,
`expiresIn` e todos os contadores do dashboard são `boolean`/`long` primitivos e por isso estão **sempre**
presentes, inclusive quando valem `false` ou `0`.

No lado da requisição, `null` é significativo em um único lugar: `PATCH /vulnerabilities/{id}/assignee` com
`{"userId": null}` é como se desatribui um item.

### Envelope de listagem paginada

Todas as listagens paginadas (`/projects`, `/assets`, `/vulnerabilities`, `/vulnerabilities/{id}/comments`,
`/audit-logs`, `/scan-imports`) devolvem `PageResponse`:

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

`sort` ecoa a ordenação **efetivamente aplicada** pelo servidor, já depois da sanitização — é por ele que se
confere se o `sort` enviado foi aceito (veja a allowlist abaixo). Com múltiplas ordenações os pares vêm
separados por `;`, por exemplo `"createdAt,asc;id,asc"`. Se a ordenação for vazia, a chave `sort` é omitida
(regra `non_null`).

Duas listagens **não** usam este envelope, de propósito:

- `GET /users` devolve um array puro: a lista é curta, limitada à empresa, e existe para preencher um seletor
  de responsável.
- `GET /dashboard/severity-distribution` e `GET /dashboard/status-distribution` devolvem arrays fixos de 4
  elementos (um por valor do enum). Envolver um agregado de tamanho constante em `page`/`size`/`totalPages`
  daria ao cliente cinco campos constantes e nenhuma ação possível. A deviation está registrada no javadoc de
  `SeverityDistributionResponse`.

### Envelope de erro

Toda falha — validação, autenticação, autorização, conflito, erro inesperado — responde `ApiError`:

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

`fieldErrors` só existe em erros de validação; nos demais a chave é omitida. `traceId` casa com o header
`X-Request-Id` da resposta e com a linha de log correspondente.

Valores possíveis de `code` (`ErrorCode`): `VALIDATION_ERROR`, `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`,
`NOT_FOUND`, `CONFLICT`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`.

### Paginação e ordenação

| Parâmetro | Padrão | Observação |
| --- | --- | --- |
| `page` | `0` | Valores negativos são elevados a `0` |
| `size` | `20` | Limitado ao intervalo `[1, 100]` (`PageableSupport.MAX_PAGE_SIZE`) |
| `sort` | por módulo | `sort=campo,asc` ou `sort=campo,desc`; repetir o parâmetro para ordenar por mais de um campo |

**A allowlist de ordenação falha em silêncio, por design.** `PageableSupport.sanitize` descarta qualquer
propriedade fora da lista do módulo; se sobrar nenhuma, aplica a ordenação padrão. Um `sort=passwordHash,asc`
ou `sort=company.name,asc` não vira 400 nem 500 — vira a ordenação padrão. O motivo é que a propriedade
enviada pelo cliente chega ao Spring Data como um caminho de atributo de entidade; aceitar qualquer string
transformaria o parâmetro em um caminho arbitrário até associações não previstas. Como o descarte é
silencioso, **confira a chave `sort` da resposta** para saber o que o servidor realmente usou.

| Módulo | Propriedades ordenáveis | Ordenação padrão |
| --- | --- | --- |
| Projetos | `name`, `status`, `createdAt`, `updatedAt` | `createdAt,desc` |
| Ativos | `name`, `type`, `environment`, `criticality`, `createdAt`, `updatedAt` | `createdAt,desc` |
| Vulnerabilidades | `title`, `severity`, `status`, `cvssScore`, `discoveredAt`, `dueDate`, `resolvedAt`, `createdAt`, `updatedAt` | `createdAt,desc` |
| Comentários | `createdAt` | `createdAt,asc;id,asc` |
| Auditoria | `createdAt`, `action`, `entityType` | `createdAt,desc` |
| Importações | `createdAt`, `updatedAt`, `status`, `format`, `sizeBytes`, `totalFindings` | `createdAt,desc` |
| Usuários | — (sem paginação e sem `sort`) | sempre `name,asc` |

A ordenação ascendente dos comentários é deliberada: uma discussão se lê do mais antigo para o mais novo,
ao contrário do resto da API. O `id` entra como desempate para que dois comentários gravados no mesmo
microssegundo não alternem de página entre duas leituras.

### Enums

| Enum | Valores |
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

`Severity` e `Criticality` listam os mesmos quatro níveis mas são enums distintos: criticidade descreve o
quanto um ativo importa, severidade o quanto um achado é grave.

Um valor de enum inválido em query ou corpo responde **400 `BAD_REQUEST`** ("Requisição malformada"), sem
`fieldErrors`, porque a falha acontece na desserialização, antes da validação.

---

## Autenticação

| Método | Endpoint | Acesso |
| --- | --- | --- |
| POST | `/auth/register` | público |
| POST | `/auth/login` | público |
| GET | `/auth/me` | autenticado (qualquer papel) |

### POST /auth/register

Cria a empresa e o seu primeiro usuário, sempre com papel `ADMIN`. É o único caminho para criar uma empresa.

Validações: `companyName` e `name` de 2 a 120 caracteres, `email` válido com até 180, `password` de **10 a
100 caracteres**.

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

Note o que **não** está ali: `refreshToken` é nulo no MVP (refresh é V2) e some pela regra `non_null`;
`lastLoginAt` ainda não existe para um usuário recém-criado; `createdAt` do usuário aparece a partir da
leitura em `/auth/me`. `expiresIn` é em segundos e reflete `securityhub.jwt.expiration-minutes` (60 por
padrão).

O e-mail é único **globalmente**, não por empresa (ver `docs/adr/0004-global-email-uniqueness.md`), então um
e-mail já usado em outra empresa responde `409 CONFLICT` com "E-mail já cadastrado".

### POST /auth/login

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "admin@demo.test",
  "password": "Demo@SecurityHub2026"
}
```

`200 OK` — mesma forma do register, agora com `lastLoginAt` preenchido no próximo `/auth/me`.

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

Credencial errada, e-mail inexistente ou usuário desativado respondem todos o mesmo `401 UNAUTHORIZED` com
"Credenciais inválidas". Quando o e-mail não existe o serviço ainda verifica a senha contra um hash descartável
para que o tempo de resposta não diferencie os dois casos — o endpoint não serve para enumerar contas.

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

## Projetos

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/projects` | qualquer papel autenticado |
| GET | `/projects/{id}` | qualquer papel autenticado |
| POST | `/projects` | `ADMIN` |
| PUT | `/projects/{id}` | `ADMIN` |
| DELETE | `/projects/{id}` | `ADMIN` |

### GET /projects

`GET /projects?page=&size=&sort=&search=&status=`

| Parâmetro | Tipo | Efeito |
| --- | --- | --- |
| `search` | texto | `contains` sem distinguir maiúsculas em `name` **ou** `description` |
| `status` | `ProjectStatus` | igualdade exata |
| `page`, `size`, `sort` | — | veja "Paginação e ordenação"; ordenáveis: `name`, `status`, `createdAt`, `updatedAt` |

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

`assetCount` vem de uma única query agrupada para a página inteira, não de uma coleção mapeada — por isso não
há N+1 ao listar projetos. Um projeto sem descrição não traz a chave `description`.

### POST /projects

`status` é opcional na criação; ausente significa `ACTIVE`. `name` tem de 2 a 140 caracteres, `description`
até 2000. Não há `companyId` no corpo, nem aqui nem em nenhum outro endpoint: o tenant sai do token.

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

Nome repetido na mesma empresa (comparação sem distinguir maiúsculas) responde `409 CONFLICT`: "Já existe um
projeto com esse nome nesta empresa".

### GET /projects/{id}

`200 OK` com o mesmo objeto acima. Um id de outra empresa responde `404` — veja "Isolamento entre empresas".

### PUT /projects/{id}

Substituição completa: `name` é obrigatório, `description` ausente **apaga** a descrição, e `status` ausente
**preserva** o status atual (é o único campo cuja ausência não zera o valor).

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

`200 OK` com o projeto atualizado (mesma forma do `POST`).

### DELETE /projects/{id}

`204 No Content`, sem corpo.

Um projeto que ainda tem ativos responde `409 CONFLICT` com a contagem na mensagem: "O projeto possui 3
ativo(s) e não pode ser excluído". A regra do produto é não apagar filhos em cascata silenciosamente;
a chave estrangeira não tem `ON DELETE CASCADE`, então a alternativa seria uma violação de integridade crua
em vez de um conflito legível.

---

## Ativos

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/assets` | qualquer papel autenticado |
| GET | `/assets/{id}` | qualquer papel autenticado |
| POST | `/assets` | `ADMIN` |
| PUT | `/assets/{id}` | `ADMIN` |
| DELETE | `/assets/{id}` | `ADMIN` |

### GET /assets

`GET /assets?page=&size=&sort=&search=&projectId=&type=&environment=&criticality=`

| Parâmetro | Tipo | Efeito |
| --- | --- | --- |
| `search` | texto | `contains` sem distinguir maiúsculas em `name`, `description` **ou** `identifier` |
| `projectId` | `Long` | ativos de um projeto |
| `type` | `AssetType` | igualdade exata |
| `environment` | `Environment` | igualdade exata |
| `criticality` | `Criticality` | igualdade exata |
| `page`, `size`, `sort` | — | ordenáveis: `name`, `type`, `environment`, `criticality`, `createdAt`, `updatedAt` |

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

O segundo ativo mostra a regra `non_null` em ação: sem descrição e sem identificador, nenhuma das duas chaves
aparece.

### POST /assets

`projectId`, `name`, `type`, `environment` e `criticality` são obrigatórios. `identifier` é opcional (até 255
caracteres) e uma string em branco é normalizada para ausente.

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

`201 Created` com o objeto de ativo e `vulnerabilityCount: 0`.

Conflitos e erros específicos:

- Identificador repetido **no mesmo projeto** (sem distinguir maiúsculas): `409 CONFLICT` — "Já existe um ativo
  com esse identificador neste projeto". Identificador ausente nunca conflita: vários ativos do mesmo projeto
  podem não ter nenhum.
- `projectId` de outra empresa ou inexistente: `404 NOT_FOUND` — "Projeto 999 não encontrado".

### PUT /assets/{id}

Aceita mover o ativo para outro projeto **da mesma empresa** enviando outro `projectId`; a checagem de
identificador único passa a rodar contra o projeto de destino. Um `projectId` de outra empresa é `404`.

### DELETE /assets/{id}

`204 No Content`. Um ativo com vulnerabilidades responde `409 CONFLICT`: "O ativo possui 4 vulnerabilidade(s)
e não pode ser excluído".

---

## Vulnerabilidades

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/vulnerabilities` | qualquer papel autenticado |
| GET | `/vulnerabilities/{id}` | qualquer papel autenticado |
| POST | `/vulnerabilities` | `ADMIN`, `ANALYST` |
| PUT | `/vulnerabilities/{id}` | `ADMIN`, `ANALYST` |
| DELETE | `/vulnerabilities/{id}` | `ADMIN` |
| PATCH | `/vulnerabilities/{id}/status` | `ADMIN`, `ANALYST`; `DEVELOPER` apenas em item atribuído a si |
| PATCH | `/vulnerabilities/{id}/assignee` | `ADMIN`, `ANALYST` |

### GET /vulnerabilities

`GET /vulnerabilities?page=&size=&sort=&search=&projectId=&assetId=&severity=&status=&assignedTo=&overdue=`

| Parâmetro | Tipo | Efeito |
| --- | --- | --- |
| `search` | texto | `contains` sem distinguir maiúsculas em `title`, `description` **ou** `cve` |
| `projectId` | `Long` | alcança o projeto através do ativo (`asset.project.id`), não por coluna denormalizada |
| `assetId` | `Long` | igualdade exata |
| `severity` | `Severity` | igualdade exata |
| `status` | `VulnerabilityStatus` | igualdade exata |
| `assignedTo` | `Long` | id do responsável |
| `overdue` | `boolean` | veja abaixo |
| `page`, `size`, `sort` | — | ordenáveis: `title`, `severity`, `status`, `cvssScore`, `discoveredAt`, `dueDate`, `resolvedAt`, `createdAt`, `updatedAt` |

**Definição de `overdue`**: `dueDate != null` **e** `dueDate < agora` **e** o status é `OPEN` ou `IN_PROGRESS`.
Uma vulnerabilidade `RESOLVED` ou `ACCEPTED_RISK` nunca está atrasada, por mais antiga que seja a data. O campo
`overdue` da resposta é calculado, nunca armazenado, e usa o mesmo instante para a página inteira — filtro e
flag de cada linha são avaliados contra o mesmo "agora".

`overdue=false` devolve tudo que **não** está atrasado, incluindo as vulnerabilidades sem `dueDate`. Isso não é
automático em SQL (`NULL < agora` é UNKNOWN), e por isso a negação é aplicada sobre a conjunção inteira em
`VulnerabilitySpecifications`.

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

Este achado ainda não foi resolvido nem atribuído: **não há chave `resolvedAt` nem chave `assignedTo`**. Já
`overdue` é primitivo e aparece sempre.

O painel "itens recentes" do dashboard é esta mesma listagem com
`?page=0&size=5&sort=createdAt,desc` — não existe um quinto endpoint de dashboard para isso.

### POST /vulnerabilities

Obrigatórios: `assetId`, `title` (3 a 200 caracteres) e `severity`. Opcionais: `description` (até 4000),
`cvssScore`, `cve`, `discoveredAt`, `dueDate`, `assignedToId`.

- `cvssScore`: `BigDecimal` entre `0.0` e `10.0`, no máximo uma casa decimal.
- `cve`: aceita o padrão `CVE-AAAA-NNNN` (com quatro ou mais dígitos finais) **ou string vazia**. A string
  vazia é aceita de propósito — um formulário reativo do Angular envia `""` para um campo opcional intocado — e
  vira `null`; o resto é normalizado para maiúsculas.
- `discoveredAt` ausente significa "descoberta agora", não a época zero.
- **Não existe `status` no corpo.** Toda vulnerabilidade nasce `OPEN`, e status só muda por
  `PATCH /status`. Aceitar `status` aqui transformaria o `PUT` — que a matriz reserva a ADMIN e ANALYST — em um
  segundo caminho, menos guardado, para levar um achado a `RESOLVED`.

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

`assetId` de outra empresa: `404` ("Ativo 999 não encontrado"). `assignedToId` desconhecido, de outra empresa
ou de um usuário desativado: `404` ("Usuário 999 não encontrado") — os três casos são indistinguíveis de
propósito.

### PUT /vulnerabilities/{id}

Mesmo corpo do `POST`. Pode mover o achado para outro ativo da mesma empresa e pode alterar o responsável via
`assignedToId` (ausente **desatribui**). Não toca em `status` nem em `resolvedAt`.

### PATCH /vulnerabilities/{id}/status

```http
PATCH /api/v1/vulnerabilities/101/status
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{ "status": "IN_PROGRESS" }
```

`200 OK` com a vulnerabilidade completa.

Regras:

- `status` é obrigatório; ausente responde `400` com `fieldErrors: [{ "field": "status", "message": "é obrigatório" }]`.
- Não há máquina de estados: **qualquer transição é aceita**, inclusive `RESOLVED → OPEN`.
- Entrar em `RESOLVED` carimba `resolvedAt` com o instante atual; sair de `RESOLVED` limpa o carimbo. A mesma
  equivalência é uma constraint `CHECK` na migração V5, então um bug aqui não consegue persistir.
- Mudar para o status que o item já tem é um no-op: responde `200` e **não** grava linha de auditoria.

Resposta após `{"status": "RESOLVED"}` — agora a chave `resolvedAt` existe e `overdue` voltou a `false`:

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

`200 OK` com a vulnerabilidade, agora com o bloco `assignedTo` (um `UserSummary`: `id`, `name`, `email`,
`role`).

`{"userId": null}` é uma requisição válida e **desatribui** o item — `userId` não é `@NotNull` justamente por
isso. Depois disso a chave `assignedTo` some da resposta.

O responsável precisa ser um usuário **ativo** da mesma empresa; qualquer outro caso é `404`.

### DELETE /vulnerabilities/{id}

`204 No Content`. Diferente de projetos e ativos, **os comentários são removidos junto** em vez de bloquear a
exclusão: não existe endpoint que apague um comentário (§6), então um conflito aqui tornaria toda vulnerabilidade
comentada permanentemente indeletável. Quantos comentários foram removidos vai para a trilha de auditoria
(`commentCount` no `oldValue`), que é o que mantém a exclusão prestável de contas.

---

## Comentários

Aninhados sob a vulnerabilidade, porque um comentário não tem significado próprio. **Não há endpoint de
exclusão** no MVP.

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/vulnerabilities/{vulnerabilityId}/comments` | qualquer papel autenticado |
| POST | `/vulnerabilities/{vulnerabilityId}/comments` | `ADMIN`, `ANALYST`, `DEVELOPER` |
| PUT | `/vulnerabilities/{vulnerabilityId}/comments/{commentId}` | autor do comentário **ou** `ADMIN` |

Em qualquer das três operações, a vulnerabilidade pai é carregada e validada contra a empresa do chamador
antes de tudo: um pai de outro tenant é `404` antes que qualquer coisa possa observar que o comentário existe.

### GET .../comments

Aceita `page`, `size` e `sort` (única propriedade ordenável: `createdAt`; padrão `createdAt,asc;id,asc`).

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

`editable` espelha no servidor a regra que o servidor vai reaplicar na edição (autor ou ADMIN) — a UI usa esse
campo para decidir se mostra o botão "editar", em vez de deduzir a regra por conta própria. Ele varia conforme
quem está lendo: o mesmo comentário vem com `editable: true` para o autor e `editable: false` para um colega
`ANALYST`.

### POST .../comments

O corpo carrega apenas o texto: a vulnerabilidade vem do caminho, o autor e a empresa vêm do principal.
`content` é obrigatório e tem até 2000 caracteres.

```http
POST /api/v1/vulnerabilities/101/comments
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{ "content": "Correção iniciada; o parâmetro passa a usar bind." }
```

`201 Created` com o objeto de comentário acima.

`VIEWER` recebe `403 FORBIDDEN` ("Acesso negado"): é estritamente somente leitura.

### PUT .../comments/{commentId}

Mesmo corpo do `POST`, `200 OK` na resposta. Quem não é o autor nem `ADMIN` recebe `403 FORBIDDEN` com
"Apenas o autor ou um administrador pode editar o comentário".

A ordem importa: o pai e o comentário são carregados **antes** da checagem de autoria, então algo fora da
empresa do chamador é `404` e o `403` só pode significar "isto existe aqui, mas não é seu".

**A trilha de auditoria não guarda o texto do comentário**, apenas `contentLength`. `AuditSanitizer` mascara
por nome de chave, não por valor, então uma credencial colada em um comentário cairia legível na trilha de
todo ADMIN. Nada se perde: sem exclusão física, o texto está sempre disponível no próprio endpoint.

---

## Usuários

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/users` | `ADMIN`, `ANALYST` |

`GET /users?role=&active=&search=`

| Parâmetro | Tipo | Efeito |
| --- | --- | --- |
| `role` | `Role` | igualdade exata |
| `active` | `boolean` | igualdade exata |
| `search` | texto | `contains` sem distinguir maiúsculas em `name` **ou** `email` |

Sem paginação e sem `sort`: sempre ordenado por `name` ascendente, sempre limitado à empresa do chamador.
`ANALYST` está incluído porque atribuir uma vulnerabilidade exige escolher um usuário.

```http
GET /api/v1/users?active=true&role=DEVELOPER
Authorization: Bearer {{accessToken}}
```

`200 OK` — array puro, sem envelope:

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

Um usuário que nunca entrou não traz a chave `lastLoginAt`.

---

## Auditoria

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/audit-logs` | `ADMIN` |

A trilha é somente leitura pela API: não existe endpoint que altere ou apague uma entrada.

`GET /audit-logs?page=&size=&sort=&entityType=&actorId=&action=&from=&to=`

| Parâmetro | Tipo | Efeito |
| --- | --- | --- |
| `entityType` | texto | igualdade exata; valores usados: `Company`, `User`, `Project`, `Asset`, `Vulnerability`, `Comment` |
| `actorId` | `Long` | id de quem agiu |
| `action` | `AuditAction` | igualdade exata |
| `from` | ISO-8601 | `createdAt >= from` |
| `to` | ISO-8601 | `createdAt <= to` |
| `page`, `size`, `sort` | — | ordenáveis: `createdAt`, `action`, `entityType`; padrão `createdAt,desc` |

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

Detalhes que valem conhecer:

- `oldValue` e `newValue` são mapas livres, gravados como texto para que a trilha preserve a forma que a
  entidade tinha na época. Um `CREATE` não tem `oldValue`, um `DELETE` não tem `newValue` — e, pela regra
  `non_null`, a chave correspondente some.
- As chaves de `snapshot` variam por ação de propósito: um `STATUS_CHANGE` grava só `status` e `resolvedAt`,
  um `ASSIGN` grava só `assignedToId` e `assignedToEmail`, um `CREATE`/`UPDATE` grava a linha inteira. Uma
  mudança de status diz o que mudou, não a entidade toda de novo.
- Campos cujo **nome** sugira credencial (`password`, `senha`, `hash`, `token`, `secret`, `credential`,
  `authorization`, `apikey`, `otp`, `cvv`, ...) são substituídos por `"***"` antes de serializar.
- `LOGIN_FAILED` é gravado em transação própria, então a tentativa fica registrada mesmo com a requisição
  terminando em `401`.
- `ipAddress` respeita `X-Forwarded-For` quando presente.

---

## Dashboard

Quatro endpoints; todos exigem apenas autenticação, porque a matriz de permissões dá "ver dashboard" aos quatro papéis.
Todos escopados pela empresa do token.

| Método | Endpoint | Acesso |
| --- | --- | --- |
| GET | `/dashboard/summary` | qualquer papel autenticado |
| GET | `/dashboard/severity-distribution` | qualquer papel autenticado |
| GET | `/dashboard/status-distribution` | qualquer papel autenticado |
| GET | `/dashboard/trend?days=30` | qualquer papel autenticado |

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

- `openVulnerabilities` **não** é uma contagem de status: é o balde "ainda acionável", `OPEN + IN_PROGRESS`.
  O detalhamento por status pertence a `/status-distribution` — dois donos para o mesmo número é como eles
  divergem.
- `overdueVulnerabilities` usa exatamente o predicado de `GET /vulnerabilities?overdue=true`.
- `topProjects` traz no máximo dez linhas, ordenadas por `total` desc e depois `projectName` asc; projetos sem
  nenhum achado não aparecem. Uma empresa sem vulnerabilidades devolve `topProjects: []`.
- Todos os contadores são primitivos e aparecem mesmo valendo `0`.

### GET /dashboard/severity-distribution

Array puro com as quatro severidades **sempre presentes**, na ordem de declaração do enum, inclusive as que
valem zero — uma legenda de gráfico que ganha e perde entradas (e embaralha as cores) entre dois recarregamentos
é pior que uma com zeros visíveis.

```json
[
  { "severity": "LOW", "count": 9 },
  { "severity": "MEDIUM", "count": 14 },
  { "severity": "HIGH", "count": 13 },
  { "severity": "CRITICAL", "count": 6 }
]
```

### GET /dashboard/status-distribution

Mesma forma, com os quatro status:

```json
[
  { "status": "OPEN", "count": 18 },
  { "status": "IN_PROGRESS", "count": 7 },
  { "status": "RESOLVED", "count": 14 },
  { "status": "ACCEPTED_RISK", "count": 3 }
]
```

### GET /dashboard/trend

`days` é opcional e vale `30` por padrão. O valor é **limitado em silêncio** ao intervalo `[1, 90]` — um
controle de gráfico não pode abrir uma caixa de erro — e a resposta ecoa a janela efetivamente usada, que é o
que mantém esse clamp visível em vez de escondido. `days=365` responde `200` com `days: 90`.

A janela é `[hoje - (days - 1), hoje]` em UTC, ambos inclusivos, então `days=30` devolve exatamente 30 pontos,
do mais antigo para o mais novo, com dias vazios presentes zerados.

`opened` conta por `discoveredAt`, `resolved` por `resolvedAt`. Nenhuma das séries usa `createdAt`: em uma base
semeada ou importada todas as linhas compartilham um `createdAt` e a tendência viraria um único pico que não
diz nada sobre o backlog.

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

## Importação de scans

| Método | Endpoint | Acesso |
| --- | --- | --- |
| POST | `/scan-imports` | `ADMIN`, `ANALYST` |
| GET | `/scan-imports/{id}` | qualquer papel autenticado |
| PATCH | `/scan-imports/{id}/findings/{findingId}` | `ADMIN`, `ANALYST` |
| POST | `/scan-imports/{id}/confirm` | `ADMIN`, `ANALYST` |
| DELETE | `/scan-imports/{id}` | `ADMIN`, `ANALYST` |
| GET | `/scan-imports` | qualquer papel autenticado |

O fluxo é **enviar, revisar, e então confirmar ou descartar**. O envio não cria nada: ele lê o
relatório, procura para cada achado um ativo do projeto escolhido, marca os achados que a
empresa já registrou e grava tudo isso como proposta. Só a confirmação cria vulnerabilidades, e
só para os achados que ainda estejam com ativo naquele momento.

Três regras explicam quase todo o resto:

- **Nenhum ativo é criado.** O alvo de um achado é comparado com o `identifier` dos ativos **do
  projeto escolhido**, sem distinguir maiúsculas e ignorando espaços nas pontas. Sem
  correspondência, o achado fica `UNMATCHED` e espera alguém dizer que ativo é aquele — inventar
  um ativo a partir de um hostname encheria o inventário de linhas sem dono.
- **Duplicado é ignorado e contado, nunca mesclado.** A impressão digital de um achado é
  `sha256(scanner:ruleId:target:cve)` — severidade e CVSS ficam de fora de propósito, porque
  mudam entre versões do scanner sem o achado ser outro. Um achado cuja impressão digital a
  empresa já carrega vira `DUPLICATE` e é ignorado na confirmação: atualizar ou reabrir a
  vulnerabilidade existente desfaria, em silêncio, o status, o responsável e a discussão que
  alguém pôs ali.
- **A importação é síncrona, com teto.** `securityhub.scan.max-findings` (padrão 2000) limita
  quantos achados um arquivo pode encenar; acima disso o envio é recusado com 400 **antes de
  qualquer gravação**, e não existe endpoint de status para consultar depois.

O que cada formato lê:

| `format` | Origem | O que vira achado | Alvo |
| --- | --- | --- | --- |
| `NMAP_XML` | `nmap -oX` | **Apenas resultado de script NSE.** Uma porta aberta não é uma vulnerabilidade, e um banner de serviço também não | hostname do host quando o nmap resolveu um, senão o endereço (nunca o MAC) |
| `ZAP_JSON` | relatório JSON do OWASP ZAP | um achado por **instância** de cada alerta; um alerta sem instâncias fica com o site | a `uri` da instância, inteira |
| `NUCLEI_JSONL` | `nuclei -jsonl` | um achado por linha; uma linha que não é JSON é pulada e o resto do arquivo continua | `matched-at`, com `host` como reserva |

O formato é **declarado por quem envia** e nunca deduzido dos bytes: os três são texto UTF-8, e
adivinhar acertaria "isto é XML" sem acertar "isto é um relatório de nmap".

### POST /scan-imports

`multipart/form-data` com três partes: `file`, `projectId` e `format`. `projectId` e `format`
são lidos como parâmetros de requisição, então também funcionam na query string.

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

`201 Created` com a importação **e todos os seus achados** — o cliente acabou de enviar o
arquivo e precisa mostrar a prévia, não fazer uma segunda chamada para buscá-la:

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

Repare no que **não** está lá: o achado 42 não tem `assetId` nem `assetName` (a regra
`non_null` tira a chave), nenhum achado tem `vulnerabilityId` enquanto a importação está
pendente, e o achado 41 não tem `cvssScore` porque o nmap não dá nota — a severidade dele é
derivada do texto do script (citar um CVE ou a palavra `VULNERABLE` é `HIGH`, o resto é
`MEDIUM`; `CRITICAL` nunca é inventado). O nome do arquivo em disco nunca é exposto.

O achado 43 chegou como `DUPLICATE` mesmo tendo encontrado o ativo: duplicado vence sobre "com
ativo", porque um duplicado com ativo continua sendo algo que a empresa já registrou.

Erros específicos:

- Arquivo vazio, ou ilegível para o interpretador do formato escolhido: `400 BAD_REQUEST` —
  "O relatório nmap enviado não é um XML válido".
- Acima de `securityhub.scan.max-findings`: `400 BAD_REQUEST` — "O relatório contém 5120 achados
  e o limite por importação é 2000; filtre o relatório no scanner (por severidade ou por host)
  ou divida-o em arquivos menores e envie um de cada vez". Nada é gravado e nenhum arquivo fica
  em disco.
- Acima de `securityhub.scan.max-upload-bytes` (padrão 10 MiB): `413 PAYLOAD_TOO_LARGE`.
- `format` fora do enum: `400 BAD_REQUEST`, na desserialização, antes de o serviço rodar.
- `projectId` de outra empresa ou inexistente: `404 NOT_FOUND` — "Projeto 999 não encontrado".
- Papel `DEVELOPER` ou `VIEWER`: `403 FORBIDDEN`.

### GET /scan-imports/{id}

A prévia: a mesma estrutura do envio, com os achados no estado em que estão agora. Os achados
não são paginados — a quantidade já está limitada por `max-findings`, e paginar a tela de
revisão pediria ao operador que mapeasse vinte de cada vez.

```http
GET /api/v1/scan-imports/12
Authorization: Bearer {{accessToken}}
```

Aberto a qualquer papel da empresa. Uma importação de outra empresa é `404`, nunca `403`.

### PATCH /scan-imports/{id}/findings/{findingId}

Dá a um achado `UNMATCHED` o ativo que o alvo dele não resolveu sozinho.

```http
PATCH /api/v1/scan-imports/12/findings/42
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "assetId": 9
}
```

`200 OK` com o achado atualizado — e só ele, porque é a única linha que mudou de estado:

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

Os contadores da importação são recalculados na mesma transação, então o próximo `GET` já traz
`matchedCount` maior e `unmatchedCount` menor.

O ativo precisa ser **da empresa**, e não necessariamente do projeto da importação: uma
varredura que reportou `10.0.0.11` pode ter acertado um ativo registrado em outro projeto do
mesmo tenant, e recusar isso deixaria o operador com um achado que ele sabe de quem é e não
consegue importar. (A tela oferece apenas os ativos do projeto da importação, que é o caso
comum; a API aceita os demais.)

Erros específicos:

- Achado que não é `UNMATCHED`: `409 CONFLICT` — "Somente um achado sem ativo pode ser mapeado;
  este está MATCHED". Um achado já resolvido não tem o que mudar, e um `DUPLICATE` não seria
  importado de todo jeito.
- Importação já confirmada ou descartada: `409 CONFLICT`.
- `assetId` de outra empresa ou inexistente: `404 NOT_FOUND` — "Ativo 999 não encontrado".
- `findingId` que não pertence a esta importação: `404 NOT_FOUND`.

### POST /scan-imports/{id}/confirm

Cria uma vulnerabilidade por achado `MATCHED`, em um único lote, e encerra a importação. Sem
corpo.

```http
POST /api/v1/scan-imports/12/confirm
Authorization: Bearer {{accessToken}}
```

`200 OK` com a importação já em `CONFIRMED`:

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

**Os contadores viram outra coisa depois da confirmação**: as cinco situações são exclusivas,
então `matchedCount` cai a zero e o que era `MATCHED` aparece em `importedCount`. Tudo que não
era `MATCHED` — o que ninguém mapeou e o que a empresa já tinha — vira `SKIPPED`.

A duplicidade é verificada **de novo** aqui, e não reaproveitada do envio: uma importação
encenada ontem pode ser confirmada depois de outra já ter criado o mesmo achado. Por isso um
achado que estava `MATCHED` na prévia pode terminar `SKIPPED`.

Cada vulnerabilidade criada nasce `OPEN`, sem responsável, com `createdBy` de quem confirmou,
e carrega a impressão digital do achado — é ela que faz a próxima importação do mesmo relatório
não criar nada.

Na trilha de auditoria isso aparece como **uma** entrada `SCAN_IMPORT` sobre `ScanImport`,
carregando os contadores, o projeto, o formato e o nome do arquivo. Não há uma `CREATE` por
vulnerabilidade: centenas de linhas idênticas enterrariam a trilha, e a rastreabilidade por
achado está em `scan_findings.vulnerability_id`, que a prévia devolve como `vulnerabilityId`.

Erros específicos:

- Importação que não está `PENDING`: `409 CONFLICT` — "Não é possível confirmar uma importação
  com status CONFIRMED; apenas importações pendentes podem ser alteradas". Uma segunda
  confirmação **não** é um sucesso idempotente: a primeira criou linhas, e responder 200 diria a
  um cliente que repetiu a chamada que ela também criou.

### DELETE /scan-imports/{id}

Descarta uma importação pendente. `204 No Content`.

```http
DELETE /api/v1/scan-imports/12
Authorization: Bearer {{accessToken}}
```

Apesar do verbo, **nada é excluído do histórico**: a importação passa a `DISCARDED` e continua
listada, com os achados que o relatório trouxe. O que some é o arquivo em disco, removido
depois do commit — os contadores de uma importação descartada são como alguém responde, meses
depois, "sim, varremos aquele host, e escolhemos não importar".

Uma importação confirmada responde `409 CONFLICT`: o arquivo dela sustenta vulnerabilidades que
existem, e é o único desta funcionalidade que ganhou o direito de ficar.

### GET /scan-imports

Histórico paginado da empresa, mais novo primeiro. **Sem os achados** — uma página de vinte
importações carregando todos os achados de cada uma seriam milhares de linhas para desenhar
seis números.

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

`importedByName` é o nome de quem importou, nunca o id nem o e-mail. Os seis contadores são
primitivos e por isso aparecem sempre, inclusive zerados.

---

## Passo a passo ponta a ponta

Este é o mesmo fluxo que `scripts/smoke-test.sh` executa contra uma pilha rodando, e o mesmo que
`docs/http/securityhub.http` executa de cima para baixo. Os ids são os do exemplo; substitua pelos seus.

### 1. Cadastro da empresa

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

`201` — o primeiro usuário é sempre `ADMIN`. Guarde `user.id` (aqui: `12`); ele será o responsável no passo 5.

### 2. Login

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "email": "admin@acme.test", "password": "uma-senha-suficientemente-longa" }
```

`200` — guarde `accessToken`. Todas as chamadas seguintes levam `Authorization: Bearer <accessToken>`.

### 3. Criar o projeto

```http
POST /api/v1/projects
{ "name": "Projeto Acme", "description": "Criado no passo a passo" }
```

`201` → `id: 7`. Sem `status` no corpo, o projeto nasce `ACTIVE` com `assetCount: 0`.

### 4. Criar o ativo

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

### 5. Criar a vulnerabilidade

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

`201` → `id: 101`, `status: "OPEN"`, sem `resolvedAt` e sem `assignedTo`.

### 6. Atribuir

```http
PATCH /api/v1/vulnerabilities/101/assignee
{ "userId": 12 }
```

`200` — a resposta ganha o bloco `assignedTo` com `id`, `name`, `email` e `role`.

### 7. Mover para em andamento

```http
PATCH /api/v1/vulnerabilities/101/status
{ "status": "IN_PROGRESS" }
```

`200` — `status: "IN_PROGRESS"`; ainda sem `resolvedAt`.

### 8. Comentar

```http
POST /api/v1/vulnerabilities/101/comments
{ "content": "Correção iniciada; o parâmetro passa a usar bind." }
```

`201` → `id: 55`, com `author` e `editable: true` (você é o autor).

### 9. Resolver

```http
PATCH /api/v1/vulnerabilities/101/status
{ "status": "RESOLVED" }
```

`200` — agora a chave `resolvedAt` **aparece**, carimbada com o instante da transição, e `overdue` é `false`.

### 10. Ler a trilha de auditoria

```http
GET /api/v1/audit-logs?size=50
```

`200` — a trilha já contém, do mais recente para o mais antigo: `STATUS_CHANGE` (para `RESOLVED`), `COMMENT`,
`STATUS_CHANGE` (para `IN_PROGRESS`), `ASSIGN`, `CREATE` (Vulnerability), `CREATE` (Asset), `CREATE` (Project),
`LOGIN` e `REGISTER`. Nenhum campo sensível aparece em claro.

### 11. Ler o dashboard

```http
GET /api/v1/dashboard/summary
```

`200` — `totalProjects: 1`, `totalAssets: 1`, `totalVulnerabilities: 1`, `resolvedVulnerabilities: 1`,
`openVulnerabilities: 0`, e `topProjects` com uma linha para "Projeto Acme".

Os outros três endpoints do dashboard (`severity-distribution`, `status-distribution`, `trend?days=30`)
respondem a partir dos mesmos dados.

---

## Erros que importam

### 401 — sem token, ou com token inválido/expirado

Qualquer endpoint fora da lista pública. Resposta do `RestAuthenticationEntryPoint`:

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

Um token assinado com outro segredo, expirado ou de um usuário desativado dá exatamente o mesmo corpo — a
resposta não diz qual dos casos ocorreu. A mensagem "Credenciais inválidas" (também `401`) só aparece no
`POST /auth/login`.

### 403 — autenticado, mas o papel não tem a permissão

Um `DEVELOPER` tentando criar um projeto (a matriz reserva projetos a `ADMIN`):

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

Mesma resposta para `VIEWER` comentando, `ANALYST` excluindo uma vulnerabilidade, ou qualquer papel que não
`ADMIN` consultando `/audit-logs`. A checagem vive no **serviço**, não só no controller, então um chamador que
não passe por HTTP (agendador, importador) também esbarra nela.

### 404 — recurso de outra empresa

```http
GET /api/v1/projects/7
Authorization: Bearer <token da empresa B, projeto 7 é da empresa A>
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

**Por que 404 e não 403.** Um `403` responderia "isto existe, mas não é seu" — e essa é exatamente a informação
que um atacante quer. Varrendo `/projects/1`, `/projects/2`, ... ele mapearia quais ids estão ocupados no banco
inteiro, quantos projetos o sistema tem e a que taxa nascem, sem nunca ver um único dado. A API viraria um
oráculo de enumeração. Com `404`, um recurso de outra empresa é **indistinguível de um que não existe**, e a
varredura não devolve nenhum bit.

Por isso a regra é uniforme: `require(...)` carrega a linha filtrando por `companyId` e lança `404`; nenhum
serviço de domínio lança `403` por tenant. Vale para `GET`, `PUT`, `PATCH` e `DELETE`, e também para
referências no corpo — um `projectId`, `assetId` ou `assignedToId` de outra empresa é `404`, não `403`.

Consequência intencional: o `403` fica reservado a "você está no lugar certo, mas não pode fazer isso" — papel
sem permissão, comentário de outra pessoa, achado atribuído a outro. Um `403` nunca confirma a existência de
nada fora da sua empresa.

### 409 — nome duplicado

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

Variantes: "Já existe um ativo com esse identificador neste projeto" (`POST`/`PUT /assets`) e "E-mail já
cadastrado" (`POST /auth/register`).

### 409 — excluir um pai que ainda tem filhos

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

E o equivalente para ativos: "O ativo possui 4 vulnerabilidade(s) e não pode ser excluído". Vulnerabilidade é
a exceção deliberada da regra — seus comentários são apagados junto, porque não há endpoint que apague um
comentário isoladamente.

### 400 — validação com `fieldErrors`

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

Todos os campos inválidos vêm de uma vez; a ordem do array não é garantida. `message` é sempre "Dados
inválidos" — a informação acionável está em `fieldErrors`.

Não confunda com o outro `400`: JSON malformado, enum inválido (`"severity": "URGENTE"`) ou tipo incompatível
na query (`?projectId=abc`) respondem `code: "BAD_REQUEST"`, `message: "Requisição malformada"` e **sem**
`fieldErrors`, porque a falha acontece na desserialização, antes da validação rodar.

---

## A regra de propriedade do DEVELOPER

A matriz de permissões dá ao `DEVELOPER` uma única permissão de escrita sobre vulnerabilidades: **alterar o status de um
item atribuído a ele próprio** (além de comentar). Ele não cria, não edita, não exclui e não atribui.

A regra não cabe em uma anotação, porque depende da linha: `@PreAuthorize("hasAnyRole('ADMIN','ANALYST','DEVELOPER')")`
é só o portão grosso que deixa o `DEVELOPER` entrar em `PATCH /status`; a checagem fina roda no corpo do
método, depois de carregar a vulnerabilidade.

Como isso aparece — `DEVELOPER` tentando mexer num achado atribuído a outra pessoa (ou não atribuído a
ninguém):

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

Repare que a mensagem é **específica**, diferente do "Acesso negado" genérico do `403` por papel. Isso é
seguro justamente porque a ordem das checagens garante que este `403` só pode ocorrer dentro da própria
empresa: a vulnerabilidade é carregada com `findByIdAndCompanyId` **antes** da regra de propriedade, então um
id de outro tenant já saiu como `404`. Se a checagem fosse feita com `@PostAuthorize`, o `403` apareceria
também para itens de outra empresa e vazaria a existência deles.

Pontos finos:

- Um item **não atribuído** não é "de ninguém para todos": é de ninguém, e o `DEVELOPER` recebe o mesmo `403`.
- `ADMIN` e `ANALYST` passam direto, sem a checagem de propriedade.
- `VIEWER` nunca chega aqui: o `@PreAuthorize` o barra antes, com o "Acesso negado" genérico.
- Estar atribuído **não** dá ao `DEVELOPER` acesso a `PUT /vulnerabilities/{id}` nem a `PATCH /assignee` —
  esses continuam `403` para ele. É por isso que `status` não existe no corpo do `PUT`: se existisse,
  `PUT` seria um segundo caminho para resolver um achado, com um guarda diferente.
