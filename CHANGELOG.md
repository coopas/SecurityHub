# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/);
versionamento conforme [SemVer](https://semver.org/lang/pt-BR/).

## [1.0.0] — 2026-09-17

Primeira versão publicável. Gestão multiempresa de ativos e vulnerabilidades de segurança,
com autorização por papel, trilha de auditoria e dashboard.

### Adicionado

**Autenticação e tenant**
- Cadastro transacional de empresa com o primeiro administrador, e login com JWT assinado
  em HS256 e senhas em BCrypt custo 12.
- Filtro JWT que revalida assinatura, expiração, papel, empresa e situação do usuário a
  cada requisição.
- Isolamento por empresa em todos os repositórios: o `companyId` vem sempre do token, e
  recurso de outra empresa responde 404, nunca 403.
- Autorização por papel (ADMIN, ANALYST, DEVELOPER, VIEWER) aplicada em métodos de serviço.

**Domínio**
- CRUD de projetos, ativos e vulnerabilidades com busca, filtros, ordenação e paginação
  server-side, com allowlist de ordenação e limite de tamanho de página.
- Atribuição de responsável restrita a usuário ativo da mesma empresa.
- Transição de status com `resolvedAt` preenchido ao entrar em `RESOLVED` e limpo ao sair,
  garantido também por constraint no banco.
- Comentários em vulnerabilidades, com edição restrita ao autor ou a um administrador.
- Exclusão de pai com filhos bloqueada com conflito legível, em vez de cascata silenciosa.

**Auditoria**
- Trilha append-only com ator, horário, entidade, valores antes e depois, e endereço de
  origem, sanitizando campos sensíveis por nome de chave.
- Consulta paginada com filtros, exclusiva de administradores.

**Dashboard**
- Resumo, distribuição por severidade e por status, e série temporal diária de achados
  abertos e resolvidos, com três índices adicionados a partir de medição real.

**Interface**
- Angular 16 com carregamento lazy por funcionalidade: login, registro, dashboard,
  projetos, ativos, vulnerabilidades e auditoria, além das páginas 403 e 404.
- Filtros refletidos na URL, estados de carregamento, vazio e erro em toda tela assíncrona,
  confirmação antes de excluir, e severidade e status sempre com ícone e texto.

**Infraestrutura e documentação**
- Docker Compose com PostgreSQL 15, backend e frontend, todos com healthcheck.
- Seed idempotente do perfil `demo` com duas empresas e um usuário por papel.
- Arquitetura, DER, matriz de permissões, exemplos de API, análise de dependências, ADRs e
  coleções `.http` e Postman.
- Script de smoke test cobrindo o fluxo completo e o isolamento entre empresas.

### Corrigido

- **`recordIndependently` da auditoria não era independente.** Os métodos de
  `AuditLogWriter` eram package-private, e o Spring só aplica `@Transactional` a métodos
  públicos, então `REQUIRES_NEW` era silenciosamente ignorado. Como consequência, um login
  que falhava não deixava rastro, e uma falha ao gravar a trilha derrubava a requisição do
  chamador com 500.
- **Filtros opcionais quebravam quando deixados em branco.** O PostgreSQL não infere o tipo
  de um parâmetro nulo em `:param is null or ...`; os filtros passaram a ser montados com a
  Criteria API.
- **`overdue=false` omitia vulnerabilidades sem prazo.** Negar apenas a comparação de data
  resulta em `UNKNOWN` no SQL; o predicado passou a ser negado como conjunção completa.
- **A busca da listagem não reaplicava um termo idêntico** depois de limpar os filtros,
  porque `distinctUntilChanged` guardava um valor que a rota já havia sobrescrito.

### Segurança

- Driver PostgreSQL elevado de 42.3.8 para 42.7.7, acima da CVE-2024-1597 (CVSS 10.0). Não
  era explorável nesta aplicação, que usa o modo de consulta estendido padrão.
- Nenhuma vulnerabilidade crítica nas dependências de produção. As demais estão analisadas
  e justificadas em `docs/security-dependencies.md`.

### Limitações conhecidas

- Sem refresh token, recuperação de senha ou gestão de usuários pela interface. O login
  usa um access token de vida curta e os usuários são criados no cadastro da empresa.
- `GET /users` e as distribuições do dashboard devolvem array puro em vez do envelope
  paginado, por serem agregados de tamanho fixo. A decisão está registrada no código.

[1.0.0]: https://github.com/coopas/SecurityHub/releases/tag/v1.0.0
