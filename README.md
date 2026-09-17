# SecurityHub

Aplicação web para gestão de ativos e vulnerabilidades de segurança, com isolamento
entre empresas, autorização por papel e trilha de auditoria.

> Status: em construção. Consulte the project rules para o plano de execução e o estado atual.

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
um Docker acessível pelo usuário corrente.

## Estrutura

```text
backend/    API Spring Boot, organizada por funcionalidade
frontend/   SPA Angular com carregamento lazy por feature
docs/       ADRs, arquitetura e exemplos de API
scripts/    smoke test e utilitários de desenvolvimento
```

## Segurança

- `companyId` sempre vem do JWT validado, nunca do corpo ou da query string.
- Autorização aplicada no backend; a interface apenas reflete as permissões.
- Segredos são lidos do ambiente. O repositório não contém credenciais reais.

## Licença

MIT.
