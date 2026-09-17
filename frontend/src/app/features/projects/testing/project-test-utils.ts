import { PageResponse } from '../../../core/models';
import { Project } from '../models/project.model';

/** Fixtures dos testes; nenhuma tela usa dados simulados. */
export function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 1,
    name: 'Portal do cliente',
    description: 'Aplicação web pública',
    status: 'ACTIVE',
    assetCount: 3,
    createdByName: 'Ana Souza',
    createdAt: '2026-01-10T12:00:00Z',
    updatedAt: '2026-01-11T12:00:00Z',
    ...overrides,
  };
}

export function makeProjectPage(
  content: Project[],
  overrides: Partial<PageResponse<Project>> = {},
): PageResponse<Project> {
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
