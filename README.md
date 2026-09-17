# SecurityHub

Aplicação web multiempresa para gestão de ativos e vulnerabilidades de segurança:
cadastre projetos e ativos, registre achados, atribua responsáveis, acompanhe a correção
e audite tudo que foi feito — com isolamento estrito entre empresas.

![Dashboard](docs/screenshots/dashboard.png)

## Funcionalidades

- **Cadastro e autenticação** — registro de empresa com o primeiro administrador, login
  com JWT e BCrypt, e sessão validada contra o banco a cada requisição.
- **Isolamento entre empresas** — o `companyId` vem sempre do token assinado. Um recurso
  de outra empresa responde **404, nunca 403**, para que a API não vire um oráculo de
  enumeração.
- **Quatro papéis com permissões distintas** — ADMIN, ANALYST, DEVELOPER e VIEWER,
  aplicados no backend (`docs/permissions.md`). Um DEVELOPER só altera o status do que
  está atribuído a ele.
- **Projetos, ativos e vulnerabilidades** — CRUD completo com busca, filtros combináveis,
  ordenação e paginação server-side, tudo refletido na URL.
- **Fluxo de correção** — atribuição de responsável, transição de status com `resolvedAt`
  automático, comentários com edição restrita ao autor ou a um administrador.
- **Dashboard** — cards, distribuição por severidade e status, tendência diária de
  achados abertos e resolvidos, e os últimos registros.
- **Trilha de auditoria** — append-only, exclusiva de administradores, com comparação
  antes/depois e sem nunca gravar segredos.
- **Documentação de API** — OpenAPI/Swagger, exemplos em `docs/api-examples.md` e
  coleções `.http` e Postman prontas para executar.

### Telas

| | |
| --- | --- |
| ![Vulnerabilidades](docs/screenshots/vulnerabilities.png) | ![Auditoria](docs/screenshots/audit.png) |
| Lista de vulnerabilidades com filtros e chips acessíveis | Trilha de auditoria com comparação antes/depois |
| ![Ativos](docs/screenshots/assets.png) | ![Login](docs/screenshots/login.png) |
| Ativos por projeto, tipo, ambiente e criticidade | Autenticação |

O layout é responsivo: [o mesmo dashboard em largura de tablet](docs/screenshots/dashboard-tablet.png).

## Credenciais de demonstração

O perfil `demo` — que é o padrão do Docker Compose — popula duas empresas com dados
realistas. Todas as contas usam a mesma senha:

| E-mail | Papel | Empresa |
| --- | --- | --- |
| `admin@demo.test` | ADMIN | Demo Security |
| `analyst@demo.test` | ANALYST | Demo Security |
| `developer@demo.test` | DEVELOPER | Demo Security |
| `viewer@demo.test` | VIEWER | Demo Security |
| `admin@northwind.test` | ADMIN | Northwind Labs |

Senha: `Demo@SecurityHub2026`

> Esta credencial é **pública e de demonstração local**, não um segredo: ela protege dados
> sintéticos em um banco que você acabou de criar na sua máquina. Para expor a demo em
> qualquer lugar acessível, defina `SECURITYHUB_DEMO_PASSWORD` no `.env` ou desative o seed
> com `securityhub.demo.seed-enabled: false`. O segredo do JWT nunca é versionado.

Entre como `admin@northwind.test` para ver o isolamento na prática: outra empresa, outro
dashboard, nenhum dado em comum.

## Stack

| Camada | Tecnologia |
| --- | --- |
| Backend | Java 11, Spring Boot 2.7.18, Spring Security 5.7, Spring Data JPA |
| Banco | PostgreSQL 15 com migrations Flyway |
| API | REST `/api/v1`, Bean Validation, OpenAPI/Swagger (springdoc 1.7) |
| Frontend | Angular 16 (TypeScript strict), Angular Material 16, RxJS |
| Testes | JUnit 5, Mockito, Testcontainers, Jasmine/Karma |
| Infra | Docker e Docker Compose |

As versões acima são fixas. O projeto **não** usa Java 17, Spring Boot 3 nem `jakarta.*`
— veja `docs/adr/0001-stack-java-11-spring-boot-2-7.md`.

## Executar com Docker Compose

Requer Docker e Docker Compose v2.

```bash
cp .env.example .env
# gere um segredo real para o JWT
sed -i "s|^SECURITYHUB_JWT_SECRET=.*|SECURITYHUB_JWT_SECRET=$(openssl rand -base64 48)|" .env

docker compose up --build
```

| Serviço | URL |
| --- | --- |
| Frontend | http://localhost:8081 |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

Para parar e remover os volumes: `docker compose down -v`.

## Executar sem Docker

### Pré-requisitos

- JDK 11 (`JAVA_HOME` apontando para uma JDK 11)
- Node 18 (o arquivo `frontend/.nvmrc` fixa 18.20.8; com nvm basta `nvm use`)
- PostgreSQL 15 acessível

### Backend

```bash
cd backend
export SECURITYHUB_JWT_SECRET="$(openssl rand -base64 48)"
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Variáveis de conexão (com os padrões usados em desenvolvimento):
`DB_HOST=localhost`, `DB_PORT=5432`, `DB_NAME=securityhub`, `DB_USER=securityhub`,
`DB_PASSWORD=securityhub`.

O Flyway cria todo o schema na primeira execução contra um banco vazio.

### Frontend

```bash
cd frontend
nvm use          # opcional, fixa o Node 18
npm ci
npm start        # http://localhost:4200, com proxy de /api para o backend
```

## Gates de validação

```bash
cd backend  && ./mvnw test        # testes unitários e de integração
cd backend  && ./mvnw verify      # + relatório JaCoCo
cd frontend && npm ci
cd frontend && npm run lint
cd frontend && npm run test:ci    # Karma headless (Chromium via puppeteer)
cd frontend && npm run build
docker compose config
```

Os testes de integração sobem um PostgreSQL 15 real via Testcontainers, portanto exigem
um Docker acessível pelo usuário corrente. Nenhum teste usa H2.

### Docker acessível sem sudo

Se `./mvnw test` falhar com `Could not find a valid Docker environment`, confirme que o
socket responde para o seu usuário:

```bash
docker ps                     # se der "permission denied", falta o grupo
sudo usermod -aG docker $USER # e reinicie a sessão do terminal
```

### Engine Docker anterior à 25.0

O cliente docker-java embutido no Testcontainers negocia a API 1.32, que os Engines
recentes recusam, então o `pom.xml` fixa a API 1.44 (`docs/adr/0005`). Em um Engine mais
antigo que 25.0, sobrescreva:

```bash
cd backend && ./mvnw test -Ddocker.api.version=1.41
```

### Toolchain sem instalação global

Se o JDK 11 e o Node 18 não estiverem no `PATH` da máquina, aponte para eles antes de
rodar os gates. **O Node precisa ser o 18**: versões mais novas quebram o Karma do
Angular 16.

```bash
export JAVA_HOME="/caminho/para/jdk-11"
export PATH="$JAVA_HOME/bin:$PATH"                      # backend
export PATH="$HOME/.nvm/versions/node/v18.20.8/bin:$PATH" # frontend (ver frontend/.nvmrc)
```

O Maven não precisa estar instalado: `./mvnw` baixa e reutiliza a distribuição fixada em
`backend/.mvn/wrapper/maven-wrapper.properties`.

## Estrutura

```text
backend/    API Spring Boot, organizada por funcionalidade
frontend/   SPA Angular com carregamento lazy por feature
docs/       ADRs, arquitetura e exemplos de API
scripts/    smoke test e utilitários de desenvolvimento
```

## Documentação

| Documento | Conteúdo |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | Camadas, isolamento por empresa e semântica da auditoria |
| [`docs/data-model.md`](docs/data-model.md) | DER e as decisões de modelagem que não são óbvias nas migrations |
| [`docs/permissions.md`](docs/permissions.md) | Matriz de permissões e **onde cada regra é aplicada no código** |
| [`docs/api-examples.md`](docs/api-examples.md) | Exemplos de requisição e resposta de toda a API |
| [`docs/security-dependencies.md`](docs/security-dependencies.md) | Análise de dependências e exceções justificadas |
| [`docs/adr/`](docs/adr/) | Decisões de arquitetura registradas |
| [`docs/http/`](docs/http/) | Coleções `.http` e Postman, executáveis de ponta a ponta |

## Segurança

- `companyId` sempre vem do JWT validado, nunca do corpo, da query string ou de um header,
  e é revalidado contra a linha do usuário a cada requisição.
- Acesso a dado de outra empresa responde **404, nunca 403**: um 403 confirmaria que o
  registro existe.
- Autorização aplicada em métodos de serviço com `@PreAuthorize` mais checagens explícitas
  de posse. Esconder um botão no Angular não é um controle, e os testes negativos chamam a
  API diretamente com o papel errado.
- Senhas com BCrypt custo 12. O login devolve a mesma mensagem para e-mail inexistente,
  senha errada e conta desativada, e faz um hash descartável para equalizar o tempo.
- A aplicação recusa iniciar sem um segredo de JWT de pelo menos 32 bytes.
- A trilha de auditoria é append-only e sanitiza campos sensíveis por nome de chave. O
  comentário registra apenas o tamanho do conteúdo, nunca o texto, justamente porque a
  máscara é por chave e não por valor.
- Erros nunca expõem stack trace, SQL ou detalhe interno; cada resposta carrega um
  `traceId` correlacionável ao log.
- Segredos vêm do ambiente. O repositório não contém credencial real — veja a ressalva
  sobre a senha de demonstração acima.

## Roadmap

O escopo entregue é a **V1**, descrito no [`CHANGELOG.md`](CHANGELOG.md).

- **V2** — refresh token com rotação e revogação, recuperação de senha, convites e gestão
  de usuários, busca textual avançada, exportação CSV, anexos, testes E2E com Cypress e
  observabilidade.
- **V3** — importação de relatórios de Nmap, OWASP ZAP e Nuclei, com preview, deduplicação
  por fingerprint, histórico de importações e processamento assíncrono.

## Licença

MIT — veja [`LICENSE`](LICENSE).
