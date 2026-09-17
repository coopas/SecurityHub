export type ProjectStatus = 'ACTIVE' | 'ARCHIVED';

/** Projeto retornado pela API. O backend omite campos nulos, daí os opcionais. */
export interface Project {
  id: number;
  name: string;
  description?: string;
  status: ProjectStatus;
  assetCount: number;
  createdByName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectRequest {
  name: string;
  description?: string;
  status?: ProjectStatus;
}

/** Propriedades aceitas pelo backend em `sort`; qualquer outra é ignorada pelo servidor. */
export const PROJECT_SORTABLE_PROPERTIES = ['name', 'status', 'createdAt', 'updatedAt'] as const;

export type ProjectSortProperty = (typeof PROJECT_SORTABLE_PROPERTIES)[number];

export type SortDirection = 'asc' | 'desc';

/** Filtros da listagem, espelhados nos query params da URL. */
export interface ProjectQuery {
  page: number;
  size: number;
  sort: string;
  search?: string;
  status?: ProjectStatus;
}

export const PROJECT_STATUS_LABELS: Readonly<Record<ProjectStatus, string>> = {
  ACTIVE: 'Ativo',
  ARCHIVED: 'Arquivado',
};

export const PROJECT_STATUSES: readonly ProjectStatus[] = ['ACTIVE', 'ARCHIVED'];
