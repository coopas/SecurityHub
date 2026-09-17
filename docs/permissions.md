# Matriz de permissões

Este documento é a fonte normativa da matriz de permissões e registra **onde cada regra
é aplicada no código**, que é o que importa em uma revisão de segurança.

## Princípio

> Esconder um botão no Angular não é um controle.

Toda regra abaixo é aplicada no backend. A interface apenas reflete o que o backend já
garante; qualquer chamada direta à API com um token de papel insuficiente recebe 403 —
e isso é testado.

## Papéis

| Papel | Intenção |
| --- | --- |
| `ADMIN` | Administra a empresa: usuários, projetos, ativos, exclusões e auditoria. |
| `ANALYST` | Trabalha o backlog de segurança: cria, edita, atribui e classifica vulnerabilidades. |
| `DEVELOPER` | Corrige o que lhe foi atribuído: muda o status dos **próprios** itens e comenta. |
| `VIEWER` | Estritamente somente leitura. |

## Matriz

| Ação | ADMIN | ANALYST | DEVELOPER | VIEWER | Onde é aplicada |
| --- | :---: | :---: | :---: | :---: | --- |
| Ver dashboard, projetos, ativos, vulnerabilidades | ✓ | ✓ | ✓ | ✓ | `SecurityConfig.anyRequest().authenticated()` |
| Listar usuários | ✓ | ✓ | — | — | `UserService.search` |
| Criar/editar/excluir projeto | ✓ | — | — | — | `ProjectService.{create,update,delete}` |
| Criar/editar/excluir ativo | ✓ | — | — | — | `AssetService.{create,update,delete}` |
| Criar/editar vulnerabilidade | ✓ | ✓ | — | — | `VulnerabilityService.{create,update}` |
| Excluir vulnerabilidade | ✓ | — | — | — | `VulnerabilityService.delete` |
| Atribuir vulnerabilidade | ✓ | ✓ | — | — | `VulnerabilityService.assign` |
| Alterar **qualquer** status | ✓ | ✓ | — | — | `VulnerabilityService.ensureCanChangeStatus` |
| Alterar status de item **atribuído a si** | ✓ | ✓ | ✓ | — | `VulnerabilityService.ensureCanChangeStatus` |
| Comentar | ✓ | ✓ | ✓ | — | `CommentService.create` |
| Editar comentário (autor ou ADMIN) | ✓ | autor | autor | — | `CommentService.ensureCanEdit` |
| Consultar auditoria | ✓ | — | — | — | `AuditQueryService.search` |

## Como as regras são expressas

### Papel: `@PreAuthorize` em métodos de **serviço**
Nunca em controllers. O controller é uma casca sem regra de negócio, e colocar a anotação no
serviço garante que qualquer chamador — incluindo um serviço interno futuro — passe pelo mesmo
controle.

```java
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
public VulnerabilityResponse create(AuthenticatedUser current, VulnerabilityRequest request) { … }
```

### Posse: checagem explícita depois de carregar a linha
A regra do `DEVELOPER` depende do valor de `assignedTo` da linha, que `@PreAuthorize` não
enxerga. A anotação funciona como porteiro grosso (barra o `VIEWER`) e a posse é verificada
no corpo:

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

**A ordem importa**: `require(...)` — que já é escopado por empresa — roda **antes** da
checagem de posse. Assim, uma vulnerabilidade de outra empresa recebe 404 e nunca 403; um 403
confirmaria que a linha existe e transformaria a API em um oráculo de enumeração.

`@PostAuthorize` foi descartado: avalia depois de o corpo já ter mutado a entidade, e o
rollback passaria a depender da ordem relativa entre o interceptor transacional e o de
method security.

### A porta dos fundos do `DEVELOPER` está fechada
Um `DEVELOPER` poderia tentar mudar status via `PUT /vulnerabilities/{id}` em vez do endpoint
de status. Três travas independentes impedem:

1. `VulnerabilityRequest` **não possui campo `status` nem `resolvedAt`** — o mapper não tem o
   que escrever. Um teste por reflexão falha se alguém adicionar o campo depois.
2. `PUT` é `hasAnyRole('ADMIN','ANALYST')`, então um `DEVELOPER` nem chega ao corpo.
3. `status` e `resolvedAt` são mutados em um único método, `changeStatus`.

### Isolamento entre empresas
`companyId` vem **sempre** do token assinado, nunca do corpo, da query ou de um header, e é
revalidado contra a linha do usuário a cada requisição por `JwtAuthenticationFilter`. Todo
finder de repositório carrega o `companyId`, e toda specification começa por `Specs.company(...)`.

Acesso a dado de outra empresa retorna **404, nunca 403**, em GET, PUT, PATCH e DELETE, e o
registro simplesmente não aparece nas listagens.

## Cobertura de testes

Testes negativos existentes: token ausente, malformado, sem prefixo `Bearer`, expirado,
assinado com outro segredo, com payload adulterado, com `role` ou `companyId` divergentes da
linha do usuário, usuário desativado no meio da sessão, papel sem permissão (403), leitura e
escrita cruzadas entre duas empresas (404), `DEVELOPER` em item de terceiro e em item não
atribuído (403), `DEVELOPER` usando `PUT` (403), e `ANALYST` editando comentário alheio (403).
