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
| Exportar vulnerabilidades em CSV | ✓ | ✓ | — | — | `VulnerabilityExportService.exportCsv` |
| Gerar relatório executivo em PDF | ✓ | ✓ | — | — | `ReportService.generateExecutivePdf` |
| Ver histórico e prévia de importações | ✓ | ✓ | ✓ | ✓ | `SecurityConfig` (autenticado); `ScanImportService.{history,preview}` |
| Enviar relatório de varredura | ✓ | ✓ | — | — | `ScanImportService.upload` |
| Vincular um achado a um ativo | ✓ | ✓ | — | — | `ScanImportService.mapFinding` |
| Confirmar uma importação | ✓ | ✓ | — | — | `ScanImportService.confirm` |
| Descartar uma importação pendente | ✓ | ✓ | — | — | `ScanImportService.discard` |
| Anexar arquivo a uma vulnerabilidade | ✓ | ✓ | ✓ | — | `AttachmentService.upload` |
| Listar e baixar anexos | ✓ | ✓ | ✓ | ✓ | `SecurityConfig` (autenticado) |
| Excluir anexo (autor ou ADMIN) | ✓ | autor | autor | — | `AttachmentService.ensureCanDelete` |
| Convidar usuário | ✓ | — | — | — | `InvitationService.invite` |
| Listar e revogar convites | ✓ | — | — | — | `InvitationService.{list,revoke}` |
| Alterar nome de usuário | ✓ | — | — | — | `UserService.rename` |
| Alterar papel de usuário | ✓ | — | — | — | `UserService.changeRole` |
| Ativar e desativar usuário | ✓ | — | — | — | `UserService.changeActive` |

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

## Regras de identidade que não são de papel

Algumas regras da V2 não dependem do papel de quem chama, e sim do estado da linha ou de quem
é o alvo. Todas vivem no serviço, depois da busca escopada por empresa.

### Um administrador não pode desativar a própria conta

Vale **sempre**, mesmo que existam outros administradores ativos. É uma regra plana justamente
para não haver caminho em que alguém se tranque para fora, e não existe caso de uso legítimo
para o contrário. Responde 409.

### A empresa precisa de pelo menos um administrador ativo

Rebaixar ou desativar o último ADMIN ativo responde 409. A contagem usa
`UserRepository.countByCompanyIdAndRoleAndActiveTrue`, que existia desde a V1 sem nenhum
chamador.

**Um convite pendente não conta.** É por isso que convite vive em tabela própria e a linha de
`users` só nasce na aceitação: se o convite fosse uma linha de usuário, um convite de ADMIN
nunca aceito satisfaria a contagem e um administrador poderia se rebaixar deixando a empresa
sem administrador real.

### O e-mail não é editável por administrador

`UserUpdateRequest` carrega apenas o nome, e um teste por reflexão falha se alguém acrescentar
o campo depois. O e-mail é o identificador de login **e** o canal de recuperação de senha: um
administrador que reaponta o endereço de um colega para a própria caixa pede uma redefinição e
assume a conta, sem que a vítima veja nada.

### Troca de papel e desativação revogam os refresh tokens do alvo

O access token já morre sozinho, porque `JwtAuthenticationFilter` relê o usuário a cada
requisição e rejeita papel divergente ou conta inativa. Mas o refresh token sobreviveria à
decisão administrativa e emitiria um access token novo, então ele é revogado explicitamente.

### A importação de scan segue a licença de criar vulnerabilidade

Enviar um relatório, vincular um achado a um ativo, confirmar e descartar são todos
`hasAnyRole('ADMIN','ANALYST')`, declarados nos métodos de `ScanImportService` — o controller
não tem nenhuma anotação de papel. O critério é simples: uma importação confirmada **é** a
criação de um lote de vulnerabilidades, e quem pode criar uma por formulário pode criar um
lote por arquivo. Um `DEVELOPER` e um `VIEWER` recebem 403 nas quatro.

Ler é aberto a qualquer membro autenticado da empresa, como as vulnerabilidades que a
importação vai gerar: `preview` e `history` não têm `@PreAuthorize`, e a tela mostra ao papel
sem permissão a mesma prévia, sem os botões, com o aviso de que ele pode acompanhar a revisão
mas não decidir.

**`DELETE /scan-imports/{id}` não é ADMIN-only, ao contrário de `DELETE /vulnerabilities/{id}`,
e a diferença não é um descuido.** Ali se apaga uma linha do backlog, que pode ter histórico,
responsável e discussão. Aqui se descarta uma *proposta*: uma importação pendente não criou
nada, e desistir dela é o passo normal de quem enviou o arquivo errado. Só uma importação
`PENDING` pode ser descartada — uma já confirmada responde 409 —, então este endpoint nunca
alcança uma vulnerabilidade existente.

No Angular, `/imports/novo` é a única rota da funcionalidade com `roleGuard`; `/imports` e
`/imports/:id` ficam abertas. É a aplicação do mesmo princípio do topo deste documento: o
guard é conveniência, e a recusa de verdade acontece de novo no backend a cada chamada.

### O DEVELOPER e o anexo

Anexar é liberado para DEVELOPER porque anexar a evidência de uma correção é o mesmo ato que
comentar, que ele já pode fazer. Excluir é do autor ou de um ADMIN — diferente de comentário,
um anexo **precisa** ser removível: alguém vai subir o arquivo errado, e ele pode conter dado
que não deveria ter sido enviado.

## Cobertura de testes

Testes negativos existentes: token ausente, malformado, sem prefixo `Bearer`, expirado,
assinado com outro segredo, com payload adulterado, com `role` ou `companyId` divergentes da
linha do usuário, usuário desativado no meio da sessão, papel sem permissão (403), leitura e
escrita cruzadas entre duas empresas (404), `DEVELOPER` em item de terceiro e em item não
atribuído (403), `DEVELOPER` usando `PUT` (403), e `ANALYST` editando comentário alheio (403).

Acrescentados na V2: refresh token reusado fora da janela de tolerância (401, com a família
inteira revogada), token de outra empresa, token de acesso apresentado no endpoint de
renovação e vice-versa, recuperação de senha respondendo idêntico para e-mail conhecido,
desconhecido e desativado, convite para endereço já existente em outra empresa (409), convite
pendente não contando como administrador ativo, autodesativação (409), último administrador
(409), tentativa de alterar e-mail por reflexão, exportação por papel sem permissão (403 com
corpo JSON), upload declarando um tipo e enviando outro (415), e travessia de caminho no nome
do arquivo.

Acrescentados com a importação de scans: `DEVELOPER` e `VIEWER` enviando relatório, vinculando
achado, confirmando e descartando (403 nos quatro, com o multipart completo — um corpo
incompleto daria 400 antes de a regra de papel rodar), importação de outra empresa em todos os
endpoints (404, inclusive no `DELETE`), projeto de outra empresa no envio (404), ativo de outra
empresa no vínculo (404), confirmação e descarte de importação já encerrada (409), vínculo em
achado que já tem ativo (409) e histórico de uma empresa não listando as importações da
outra.
