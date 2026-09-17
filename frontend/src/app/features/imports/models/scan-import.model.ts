// `Severity`, `AssetOption` e `ProjectOption` vêm das vulnerabilidades porque é lá que
// já moram: uma importação termina virando vulnerabilidade, e uma segunda declaração
// dos mesmos tipos só criaria a chance de as duas divergirem.
import {
  AssetOption,
  ProjectOption,
  Severity,
} from '../../vulnerabilities/models/vulnerability.model';

export type { AssetOption, ProjectOption, Severity };

export type ScanFormat = 'NMAP_XML' | 'ZAP_JSON' | 'NUCLEI_JSONL';

export type ScanImportStatus = 'PENDING' | 'CONFIRMED' | 'DISCARDED';

export type ScanFindingStatus = 'MATCHED' | 'UNMATCHED' | 'DUPLICATE' | 'IMPORTED' | 'SKIPPED';

/**
 * Achado bruto do relatório, já normalizado pelo backend.
 *
 * Os campos anuláveis abaixo são declarados `| null` porque é assim que o contrato os
 * descreve, mas **na resposta eles chegam ausentes, não nulos**: a API inteira serializa com
 * `default-property-inclusion: non_null` e apaga a chave. Quem for testar um deles precisa
 * tratar `undefined` — ver `hasCvss()` no componente de revisão, que existe por isso.
 */
export interface ScanFinding {
  id: number;
  ruleId: string;
  title: string;
  description: string | null;
  severity: Severity;
  cvssScore: number | null;
  cve: string | null;
  target: string;
  discoveredAt: string;
  status: ScanFindingStatus;
  assetId: number | null;
  assetName: string | null;
  vulnerabilityId: number | null;
}

/** Linha do histórico: a importação sem os achados, que só o detalhe carrega. */
export interface ScanImportSummary {
  id: number;
  projectId: number;
  projectName: string;
  format: ScanFormat;
  originalFilename: string;
  sizeBytes: number;
  status: ScanImportStatus;
  totalFindings: number;
  matchedCount: number;
  unmatchedCount: number;
  duplicateCount: number;
  importedCount: number;
  skippedCount: number;
  importedByName: string;
  createdAt: string;
  updatedAt: string;
}

export interface ScanImport extends ScanImportSummary {
  findings: ScanFinding[];
}

/** Paginação do histórico, espelhada nos query params da URL. */
export interface ScanImportQuery {
  page: number;
  size: number;
  sort: string;
}

export const SCAN_FORMATS: readonly ScanFormat[] = ['NMAP_XML', 'ZAP_JSON', 'NUCLEI_JSONL'];

export const SCAN_FORMAT_LABELS: Readonly<Record<ScanFormat, string>> = {
  NMAP_XML: 'Nmap (XML)',
  ZAP_JSON: 'OWASP ZAP (JSON)',
  NUCLEI_JSONL: 'Nuclei (JSONL)',
};

export const SCAN_FORMAT_HINTS: Readonly<Record<ScanFormat, string>> = {
  NMAP_XML: 'Saída de `nmap -oX`.',
  ZAP_JSON: 'Relatório JSON exportado pelo ZAP.',
  NUCLEI_JSONL: 'Saída de `nuclei -jsonl`, um achado por linha.',
};

/**
 * Alimenta o `accept` do seletor de arquivos, que é uma sugestão ao navegador e nada
 * mais: quem decide se o arquivo serve é o servidor, lendo o conteúdo. JSONL não tem
 * tipo registrado, por isso a extensão entra na lista.
 */
export const SCAN_FORMAT_ACCEPT: Readonly<Record<ScanFormat, string>> = {
  NMAP_XML: '.xml,text/xml,application/xml',
  ZAP_JSON: '.json,application/json',
  NUCLEI_JSONL: '.jsonl,.json,application/json',
};

export const SCAN_IMPORT_STATUS_LABELS: Readonly<Record<ScanImportStatus, string>> = {
  PENDING: 'Aguardando revisão',
  CONFIRMED: 'Confirmada',
  DISCARDED: 'Descartada',
};

/** Ícone acompanha sempre o rótulo: situação nunca é comunicada só por cor (WCAG 1.4.1). */
export const SCAN_IMPORT_STATUS_ICONS: Readonly<Record<ScanImportStatus, string>> = {
  PENDING: 'pending_actions',
  CONFIRMED: 'check_circle',
  DISCARDED: 'cancel',
};

export const SCAN_FINDING_STATUS_LABELS: Readonly<Record<ScanFindingStatus, string>> = {
  MATCHED: 'Ativo identificado',
  UNMATCHED: 'Sem ativo',
  DUPLICATE: 'Já registrado',
  IMPORTED: 'Importado',
  SKIPPED: 'Ignorado',
};

export const SCAN_FINDING_STATUS_ICONS: Readonly<Record<ScanFindingStatus, string>> = {
  MATCHED: 'link',
  UNMATCHED: 'link_off',
  DUPLICATE: 'content_copy',
  IMPORTED: 'check_circle',
  SKIPPED: 'remove_circle_outline',
};

export const SCAN_FINDING_STATUSES: readonly ScanFindingStatus[] = [
  'MATCHED',
  'UNMATCHED',
  'DUPLICATE',
  'IMPORTED',
  'SKIPPED',
];

/**
 * Espelha `ScanImportService.SORTABLE_PROPERTIES` do backend; o resto é descartado lá.
 * Repetir a lista aqui evita oferecer um cabeçalho ordenável que o servidor ignoraria em
 * silêncio, deixando a seta apontando para uma ordem que nunca acontece.
 */
export const SCAN_IMPORT_SORTABLE_PROPERTIES = [
  'createdAt',
  'updatedAt',
  'status',
  'format',
  'sizeBytes',
  'totalFindings',
] as const;

export type ScanImportSortProperty = (typeof SCAN_IMPORT_SORTABLE_PROPERTIES)[number];

export const SCAN_IMPORT_DEFAULT_SORT = 'createdAt,desc';

/**
 * `securityhub.scan.max-upload-bytes`: 10 MiB. Serve à checagem imediata antes de subir
 * o arquivo, que existe só para poupar um upload que já se sabe recusado — o limite de
 * verdade é o do servidor, que o reaplica sobre o que realmente chegou e responde
 * `PAYLOAD_TOO_LARGE`. Esta constante não protege nada, apenas economiza a espera.
 */
export const MAX_SCAN_FILE_BYTES = 10 * 1024 * 1024;

/**
 * O backend limita `size` a 100 nas listagens. Uma página basta para os dois seletores
 * desta funcionalidade: o de projetos, no envio, e o de ativos de um projeto só, na
 * prévia — e o segundo escolhe entre os ativos de um projeto, não de toda a empresa.
 */
export const OPTIONS_PAGE_SIZE = 100;
