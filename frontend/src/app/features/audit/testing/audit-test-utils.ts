import { PageResponse } from '../../../core/models';
import { AuditActorOption, AuditLog } from '../models/audit.model';

/** Test fixtures; no screen uses simulated data. */
export function makeAuditLog(overrides: Partial<AuditLog> = {}): AuditLog {
  return {
    id: 1,
    actorId: 7,
    actorEmail: 'ana@empresa.com',
    action: 'UPDATE',
    entityType: 'Vulnerability',
    entityId: 42,
    ipAddress: '203.0.113.9',
    createdAt: '2026-09-17T12:30:45Z',
    ...overrides,
  };
}

export function makeAuditPage(
  content: AuditLog[],
  overrides: Partial<PageResponse<AuditLog>> = {},
): PageResponse<AuditLog> {
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

export function makeAuditActor(overrides: Partial<AuditActorOption> = {}): AuditActorOption {
  return { id: 7, name: 'Ana Souza', email: 'ana@empresa.com', active: true, ...overrides };
}

/**
 * Vulnerability snapshot the way the backend records it: the whole object on CREATE,
 * UPDATE and DELETE. Jackson's `non_null` means a null key simply does not exist.
 */
export function makeVulnerabilitySnapshot(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    title: 'SQL Injection no login',
    description: 'Parâmetro não sanitizado',
    severity: 'HIGH',
    cvssScore: 8.1,
    cve: 'CVE-2026-0001',
    status: 'OPEN',
    discoveredAt: '2026-09-01T00:00:00Z',
    dueDate: '2026-09-30',
    assetId: 5,
    assignedToId: 7,
    ...overrides,
  };
}
