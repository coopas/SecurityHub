// `Severity`, `AssetOption` and `ProjectOption` come from vulnerabilities because that
// is where they already live: an import ends up becoming a vulnerability, and a second
// declaration of the same types would only create the chance of the two diverging.
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
 * Raw finding from the report, already normalized by the backend.
 *
 * The nullable fields below are declared `| null` because that is how the contract
 * describes them, but **in the response they arrive absent, not null**: the whole API
 * serializes with `default-property-inclusion: non_null` and drops the key. Anyone testing
 * one of them has to handle `undefined` — see `hasCvss()` in the review component, which
 * exists for exactly that reason.
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

/** History row: the import without the findings, which only the detail carries. */
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

/** History pagination, mirrored in the URL query params. */
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
 * Feeds the `accept` of the file picker, which is a hint to the browser and nothing more:
 * the one who decides whether the file is any good is the server, reading the content.
 * JSONL has no registered type, which is why the extension goes in the list.
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

/** An icon always goes with the label: status is never conveyed by color alone (WCAG 1.4.1). */
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
 * Mirrors the backend's `ScanImportService.SORTABLE_PROPERTIES`; the rest is discarded
 * there. Repeating the list here avoids offering a sortable header that the server would
 * silently ignore, leaving the arrow pointing at an order that never happens.
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
 * `securityhub.scan.max-upload-bytes`: 10 MiB. It serves the immediate check before the
 * file goes up, which exists only to spare an upload already known to be refused — the
 * real limit is the server's, which reapplies it over what actually arrived and answers
 * `PAYLOAD_TOO_LARGE`. This constant protects nothing, it only saves the wait.
 */
export const MAX_SCAN_FILE_BYTES = 10 * 1024 * 1024;

/**
 * The backend caps `size` at 100 on listings. One page is enough for the two pickers of
 * this feature: the project one, on upload, and the one for the assets of a single
 * project, on the preview — and the second picks among one project's assets, not the
 * whole company's.
 */
export const OPTIONS_PAGE_SIZE = 100;
