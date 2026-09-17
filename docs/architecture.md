# Arquitetura do SecurityHub

Este documento descreve as decisões estruturais estáveis do sistema. Decisões pontuais de
tecnologia ficam registradas em `docs/adr/`.

## 1. Visão geral

```text
┌──────────────┐      HTTPS/JSON      ┌──────────────────┐      JDBC       ┌──────────────┐
│  Angular 16  │ ───────────────────▶ │  Spring Boot 2.7 │ ──────────────▶ │ PostgreSQL 15│
│  (nginx)     │  Bearer JWT           │  Java 11         │   HikariCP      │              │
└──────────────┘                       └──────────────────┘                 └──────────────┘
```

O frontend é uma SPA servida por nginx, que também atua como proxy reverso de `/api` para o
backend — assim o navegador enxerga uma única origem e não há CORS em produção. Em
desenvolvimento, o `ng serve` cumpre o mesmo papel via `proxy.conf.json`.

O backend é um monólito modular sem estado: toda requisição carrega o próprio contexto de
autenticação no token, o que permite escalar horizontalmente sem sessão compartilhada.

## 2. Organização do backend

O código é organizado **por funcionalidade**, não por camada técnica. Cada módulo agrupa o
que muda junto:

```text
com.securityhub
├── config/          configuração transversal (JPA auditing, OpenAPI)
├── security/        JWT, filtro de autenticação, principal, CORS, filter chain
├── shared/
│   ├── error/       envelope de erro, exceções de domínio, @RestControllerAdvice, traceId
│   ├── model/       BaseEntity com timestamps auditados
│   ├── repository/  Specs — construção de filtros por Criteria API
│   └── web/         PageResponse e saneamento de Pageable
├── company/  user/  auth/
├── project/  asset/  vulnerability/  comment/
├── audit/    dashboard/
└── SecurityHubApplication
```

Dentro de um módulo: `Controller` → `Service` → `Repository`, com `dto/` e um mapper
estático. Regras de negócio moram no service; o controller apenas traduz HTTP.

### Por que entidades JPA não saem pela API

`spring.jpa.open-in-view` está **desabilitado**. A sessão do Hibernate fecha ao final do
método transacional, então o mapeamento para DTO acontece dentro do service. Isso evita
`LazyInitializationException`, impede que o formato da tabela vaze para o contrato HTTP e
elimina consultas disparadas acidentalmente durante a serialização.

## 3. Isolamento entre empresas

É a propriedade de segurança mais importante do sistema.

1. O `companyId` vive no token assinado e é revalidado contra a linha do usuário a **cada**
   requisição pelo `JwtAuthenticationFilter`. Um token cujo `companyId` ou `role` não
   confira com o banco é rejeitado, e não apenas ignorado.
2. O `companyId` **nunca** é lido do corpo, da query string ou de um cabeçalho. Um
   `companyId` enviado pelo cliente é simplesmente ignorado.
3. Toda assinatura de repositório carrega o `companyId`: não existe `findById(id)` solto na
   camada de serviço. Filtros são montados com `Specs.company(...)`/`Specs.companyColumn(...)`
   como primeiro predicado.
4. Recurso de outra empresa responde **404**, nunca 403. Um 403 confirmaria a existência do
   recurso e transformaria a API em um oráculo de enumeração.

## 4. Autenticação e autorização

- Senhas com BCrypt custo 12.
- Access token JWT HS256 contendo `sub`, `companyId`, `role`, `iat` e `exp`. O segredo vem
  de `SECURITYHUB_JWT_SECRET`; a aplicação recusa iniciar sem ele ou com menos de 32 bytes.
- O login responde a mesma mensagem genérica para e-mail inexistente, senha errada e conta
  inativa, e faz uma verificação BCrypt descartável no caso de e-mail desconhecido para
  equalizar o tempo de resposta.
- Autorização por papel é aplicada com `@PreAuthorize` **nos métodos de serviço**. A
  interface Angular esconde botões por conveniência, mas nenhuma regra depende disso: os
  testes de integração exercem cada papel contra cada endpoint.

Matriz de permissões: veja the project rules §5, que é a fonte de verdade.

## 5. Contrato HTTP

Prefixo `/api/v1`, JSON camelCase, instantes ISO-8601 em UTC.

Listagens usam sempre paginação server-side com o mesmo envelope:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "sort": "createdAt,desc" }
```

O parâmetro `sort` passa por uma allowlist por módulo (`PageableSupport.sanitize`). Uma
propriedade não prevista é descartada em vez de alcançar o Spring Data como caminho
arbitrário — evita 500 e acesso a associações não pretendidas. O tamanho de página é
limitado a 100.

Erros seguem um envelope único produzido pelo `GlobalExceptionHandler`, sem stack trace,
SQL ou detalhe interno, e com um `traceId` que também vai no cabeçalho `X-Request-Id` e em
toda linha de log da requisição.

## 6. Filtros com Criteria API

Filtros opcionais **não** são escritos como `:param is null or coluna = :param`. O
PostgreSQL não consegue inferir o tipo de um parâmetro nulo nessa posição e responde
`could not determine data type of parameter`. Cada módulo monta um `Specification` com
`shared/repository/Specs`, que simplesmente omite o predicado ausente — o que também
permite ao planejador usar os índices parciais.

## 7. Auditoria

`audit_logs` é append-only: a entidade não tem `updated_at`, nenhum endpoint escreve nela e
o repositório não é exposto à camada de API.

Há duas semânticas de gravação, e a escolha importa:

| Método | Propagação | Quando usar |
| --- | --- | --- |
| `record` | `REQUIRED` | mutações de domínio — a mudança e seu registro entram juntos ou não entram |
| `recordIndependently` | `REQUIRES_NEW` | eventos de autenticação — precisa sobreviver ao rollback de um login recusado |

Usar `REQUIRES_NEW` para linhas criadas na mesma transação quebra as chaves estrangeiras,
porque a transação independente ainda não enxerga as linhas novas.

Antes de serializar, `AuditSanitizer` substitui por `***` qualquer chave cujo nome contenha
fragmentos sensíveis (`password`, `senha`, `hash`, `token`, `secret`, `credential`, …),
recursivamente em mapas e listas.

## 8. Banco de dados

Migrations Flyway versionadas em `backend/src/main/resources/db/migration`, aplicadas na
subida da aplicação. `ddl-auto` é `validate`: o Hibernate nunca altera o schema, apenas
confere que o mapeamento corresponde ao que a migration criou.

Enums são gravados como `VARCHAR` com `CHECK`, não como tipos enum do PostgreSQL: adicionar
um valor passa a ser uma alteração de constraint, sem `ALTER TYPE` e sem travar a tabela.

Todas as chaves estrangeiras e colunas de filtro frequente têm índice, sempre com
`company_id` como primeira coluna do índice composto, que é a forma como as consultas
realmente chegam.

## 9. Frontend

Módulos Angular com carregamento lazy por feature. Estado em serviços com RxJS; não há
NgRx no MVP porque não existe estado compartilhado entre features que justifique o custo.

- `core/` — sessão, interceptors, guards, modelos compartilhados. Importado uma única vez.
- `shared/` — módulo de reexportação do Material e componentes reutilizáveis
  (`confirm-dialog`, `state-message`).
- `layout/` — o shell autenticado (toolbar, sidenav responsivo, skip link).
- `features/` — uma pasta por domínio, cada uma um módulo lazy.

O token é anexado apenas a chamadas da própria API. Um 401 fora das telas de autenticação
encerra a sessão e redireciona para `/login` preservando `returnUrl`; um 403 leva à página
`/403`. Toda tela assíncrona trata os estados de carregamento, vazio, erro e permissão.

## 10. Testes

| Nível | Ferramenta | O que cobre |
| --- | --- | --- |
| Unitário backend | JUnit 5 + Mockito | regras de negócio isoladas, sem contexto Spring |
| Integração backend | Spring Boot Test + Testcontainers | PostgreSQL 15 real, HTTP real via MockMvc, papéis e isolamento entre empresas |
| Unitário frontend | Jasmine/Karma | serviços, guards, interceptors e componentes |
| Ponta a ponta | `scripts/smoke-test.sh` | a pilha inteira em execução, com dados reais |

Os testes de integração não usam H2. Um banco em memória com dialeto diferente não
comprovaria índices parciais, `CHECK`, tipos `TIMESTAMPTZ` nem o comportamento de
parâmetros nulos que motivou a seção 6.

Cada teste roda contra um banco truncado, e não dentro de uma transação revertida, para
exercitar de fato o caminho de commit.
