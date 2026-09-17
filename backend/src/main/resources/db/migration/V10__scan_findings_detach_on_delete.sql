-- — deixa excluir uma vulnerabilidade que veio de importação.
--
-- Encontrado ao testar a pilha de verdade, depois que a V9 já estava aplicada: uma
-- vulnerabilidade criada por uma importação não podia mais ser excluída. A linha de
-- `scan_findings` que aponta para ela segurava a exclusão, o `DELETE /vulnerabilities/{id}`
-- respondia 409 e, pela regra de filhos, o ativo e o projeto dela também ficavam presos.
--
-- Uma migration nova, e não uma correção na V9, porque a V9 já rodou: o Flyway valida o
-- checksum de toda migration aplicada, e editá-la no lugar transformaria `docker compose up`
-- num ciclo de falhas para qualquer banco que já a tivesse recebido. É a mesma razão escrita
-- na V6, e ela vale igual quando quem já aplicou foi só a máquina de quem desenvolve.
--
-- O `VulnerabilityService.delete` já documenta a regra que este arquivo estende: filho sem
-- endpoint próprio de exclusão acompanha o pai em vez de bloqueá-lo, senão basta um
-- comentário para tornar o registro indestrutível. O achado importado é exatamente esse caso.

-- O achado continua IMPORTED depois que a vulnerabilidade some, e é de propósito.
--
-- A tabela `scan_findings` é o histórico do que o relatório encontrou e do que foi feito com
-- aquilo. A importação de fato importou o achado, e o `imported_count` daquela importação
-- continua verdadeiro; o que mudou depois foi o destino da vulnerabilidade, não a decisão
-- tomada na revisão. Marcá-lo como SKIPPED faria o histórico mentir sobre uma importação que
-- não pulou nada.
--
-- A equivalência anterior — IMPORTED se e somente se há vulnerabilidade — vira uma implicação
-- em um sentido só: continua impossível uma linha não importada carregar vulnerabilidade, e
-- passa a ser possível uma linha importada ter perdido a dela.
ALTER TABLE scan_findings
    DROP CONSTRAINT ck_scan_findings_status_vulnerability;

ALTER TABLE scan_findings
    ADD CONSTRAINT ck_scan_findings_status_vulnerability
        CHECK (status = 'IMPORTED' OR vulnerability_id IS NULL);

-- Quem desfaz a ligação é o banco, não o serviço.
--
-- A alternativa era o `VulnerabilityService` limpar as linhas de `scan_findings` antes de
-- excluir, como ele já faz com comentários e anexos. Aqui o banco resolve melhor: não existe
-- caminho de exclusão que possa esquecer de chamar a limpeza, e o módulo de vulnerabilidade
-- não precisa passar a conhecer o de importação só para isso.
ALTER TABLE scan_findings
    DROP CONSTRAINT fk_scan_findings_vulnerability;

ALTER TABLE scan_findings
    ADD CONSTRAINT fk_scan_findings_vulnerability
        FOREIGN KEY (vulnerability_id) REFERENCES vulnerabilities (id)
            ON DELETE SET NULL;
