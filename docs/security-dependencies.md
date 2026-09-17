# Análise de dependências

A política de segurança do projeto exige dependências sem vulnerabilidades críticas
conhecidas no momento da entrega, com exceções justificadas registradas. Este documento é
esse registro.

Data da análise: **2026-09-17**.

## Comandos

```bash
cd frontend && npm audit --omit=dev --audit-level=high   # dependências que vão para o bundle
cd frontend && npm audit                                  # inclui devDependencies
cd backend  && ./mvnw verify -Pdependency-check           # OWASP, falha em CVSS >= 9
```

`--omit=dev` é a leitura que importa para risco em produção: uma vulnerabilidade em
`karma-jasmine` não é servida a ninguém. O número cheio de `npm audit` é reportado abaixo por
transparência, não como medida de risco.

## Resultado

| Escopo | Critical | High | Moderate | Low |
| --- | :---: | :---: | :---: | :---: |
| Frontend, produção (`--omit=dev`) | **0** | 3 | 7 | 0 |
| Frontend, incluindo dev | 1 | 33 | 25 | 7 |

**O critério do §9 é atendido: zero vulnerabilidades críticas nas dependências de produção.**

## Exceção registrada: os 10 achados de produção são do próprio Angular

Os 10 achados de produção estão em `@angular/core`, `@angular/common` e `@angular/compiler`,
e os demais pacotes Angular aparecem apenas como dependentes transitivos deles. O `npm audit`
oferece uma única correção: `npm audit fix --force`, que instala **`@angular/core@22.1.7`**.

Isso é recusado, e a justificativa é a própria restrição do projeto, que fixa
**Angular 16** como parte imutável da stack. Trocar por Angular 22 seria um salto de seis
majors, exigiria reescrever build, testes e templates, e contradiz a decisão registrada em
`docs/adr/0001`. Não existe versão corrigida dentro da linha 16.

### Aplicabilidade real dos achados a esta aplicação

Registrar uma exceção sem avaliar o risco seria teatro. Cada família de advisory foi
verificada contra o código:

| Família de advisory | Vetor exigido | Presente aqui? |
| --- | --- | --- |
| Bypass de sanitização / XSS via SVG, MathML, namespace, two-way binding, host bindings | Renderizar HTML não confiável via `[innerHTML]`, `DomSanitizer` ou `bypassSecurityTrust*` | **Não.** Nenhuma ocorrência de `innerHTML`, `DomSanitizer` ou `bypassSecurityTrust` em `frontend/src`. Todo valor vindo da API é interpolado como texto. |
| `HttpTransferCache`: envenenamento de cache, colisão de chave de 32 bits, vazamento de requisição credenciada, DOM clobbering na hidratação | SSR com hidratação de cliente | **Não.** A aplicação é puramente client-side: não há `@angular/platform-server` nem `provideClientHydration`. |
| XSS via i18n (`$localize`, atributos de manipulador de evento) | Uso do i18n do Angular | **Não.** A aplicação não usa i18n do Angular; os textos são literais em português nos templates. |
| DoS por OOM em `formatDate` e `digitsInfo` | Passar formato controlado pelo usuário a essas APIs | **Não.** Nenhuma das duas é usada. O `formatDate` do dashboard é um método próprio do componente que fatia a string `yyyy-MM-dd`, sem envolver o Angular. |
| Vazamento de token XSRF por URL relativa a protocolo | Uso do `HttpClientXsrfModule` do Angular | **Não.** A autenticação é JWT stateless e o CSRF está desabilitado no backend por isso. O `AuthInterceptor` só anexa o token quando a URL começa com `environment.apiUrl`, então nunca envia credencial a uma origem de terceiro. |

**Conclusão:** nenhum dos 10 achados tem vetor alcançável nesta aplicação. A exposição é
teórica e decorre de o pacote estar na árvore, não de o código exercitar o caminho vulnerável.

### O que mudaria a conclusão

Esta análise deixa de valer se alguém introduzir `[innerHTML]`, `bypassSecurityTrust*`, SSR
com hidratação, i18n do Angular ou `HttpClientXsrfModule`. Qualquer um desses torna a
atualização do Angular um bloqueio de release, não mais uma exceção aceitável.

### Os números que incluem devDependencies

Os 66 achados do `npm audit` completo (1 crítico) vivem na cadeia de build e teste —
`@angular-devkit/build-angular`, `karma`, `webpack-dev-server`, `puppeteer` e transitivos.
Nada disso é servido ao navegador nem empacotado: o `frontend/Dockerfile` é multi-stage e a
imagem final é um `nginx:alpine` com apenas os artefatos estáticos de `dist/`. O risco é de
máquina de desenvolvedor e de runner de CI, não da aplicação publicada. Corrigi-los exige a
mesma atualização de major recusada acima.

## Backend

O profile `dependency-check` é opt-in porque baixa a base do NVD, o que torna o build lento
demais para rodar a cada commit. Ele falha em `failBuildOnCVSS >= 9`.

O arquivo `backend/dependency-check-suppressions.xml` é referenciado pelo `pom.xml` e agora
existe — antes estava ausente, então o profile falharia ao ser executado. Ele está vazio de
supressões por opção: toda supressão futura deve vir com um comentário explicando por que o
achado não se aplica.

A escolha de manter **Spring Boot 2.7.18** também é uma exceção consciente, registrada em
`docs/adr/0001`: a linha 2.7 está fora de suporte aberto (OSS), então correções de segurança
podem exigir fixar versões de dependências individualmente acima do que o BOM gerencia — foi
exatamente o que já se fez com o Flyway (`docs/adr/0002`). Migrar para Spring Boot 3 exigiria
Java 17 e `jakarta.*`, que as restrições de stack do projeto proíbem.
