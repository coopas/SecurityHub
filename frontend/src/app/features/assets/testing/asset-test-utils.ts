import { PageResponse } from '../../../core/models';
import { Asset } from '../models/asset.model';

/** Test fixtures; no screen uses simulated data. */
export function makeAsset(overrides: Partial<Asset> = {}): Asset {
  return {
    id: 1,
    name: 'API de pagamentos',
    description: 'Serviço de cobrança',
    type: 'API',
    identifier: 'api.pagamentos.local',
    environment: 'PRODUCTION',
    criticality: 'HIGH',
    projectId: 3,
    projectName: 'Portal do cliente',
    vulnerabilityCount: 0,
    createdAt: '2026-01-10T12:00:00Z',
    updatedAt: '2026-01-11T12:00:00Z',
    ...overrides,
  };
}

export function makeAssetPage(
  content: Asset[],
  overrides: Partial<PageResponse<Asset>> = {},
): PageResponse<Asset> {
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
