import { PageResponse } from '../../../core/models';
import {
  ScanFinding,
  ScanImport,
  ScanImportSummary,
} from '../models/scan-import.model';

/** Test fixtures; no screen uses simulated data. */
export function makeScanFinding(overrides: Partial<ScanFinding> = {}): ScanFinding {
  return {
    id: 1,
    ruleId: 'ssl-weak-cipher',
    title: 'Cifra TLS fraca habilitada',
    description: 'O servidor aceita suítes de cifra consideradas obsoletas.',
    severity: 'HIGH',
    cvssScore: 7.4,
    cve: 'CVE-2026-1234',
    target: 'api.pagamentos.local:443',
    discoveredAt: '2026-09-17T10:00:00Z',
    status: 'MATCHED',
    assetId: 1,
    assetName: 'API de pagamentos',
    vulnerabilityId: null,
    ...overrides,
  };
}

export function makeScanImportSummary(
  overrides: Partial<ScanImportSummary> = {},
): ScanImportSummary {
  return {
    id: 4,
    projectId: 3,
    projectName: 'Portal do cliente',
    format: 'NMAP_XML',
    originalFilename: 'varredura.xml',
    sizeBytes: 2048,
    status: 'PENDING',
    totalFindings: 3,
    matchedCount: 1,
    unmatchedCount: 1,
    duplicateCount: 1,
    importedCount: 0,
    skippedCount: 0,
    importedByName: 'Ana Souza',
    createdAt: '2026-09-17T10:05:00Z',
    updatedAt: '2026-09-17T10:05:00Z',
    ...overrides,
  };
}

export function makeScanImport(overrides: Partial<ScanImport> = {}): ScanImport {
  return {
    ...makeScanImportSummary(),
    findings: [
      makeScanFinding(),
      makeScanFinding({
        id: 2,
        ruleId: 'open-port',
        title: 'Porta 8080 exposta',
        severity: 'MEDIUM',
        cvssScore: null,
        cve: null,
        target: '10.0.0.7:8080',
        status: 'UNMATCHED',
        assetId: null,
        assetName: null,
      }),
      makeScanFinding({
        id: 3,
        ruleId: 'ssl-weak-cipher',
        title: 'Cifra TLS fraca habilitada',
        severity: 'LOW',
        status: 'DUPLICATE',
        vulnerabilityId: 91,
      }),
    ],
    ...overrides,
  };
}

export function makeScanImportPage(
  content: ScanImportSummary[],
  overrides: Partial<PageResponse<ScanImportSummary>> = {},
): PageResponse<ScanImportSummary> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    sort: 'createdAt,desc',
    ...overrides,
  };
}
