export type ProjectStatus = 'ACTIVE' | 'ARCHIVED';

/** Project returned by the API. The backend omits null fields, hence the optionals. */
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

/** Properties the backend accepts in `sort`; any other one is ignored by the server. */
export const PROJECT_SORTABLE_PROPERTIES = ['name', 'status', 'createdAt', 'updatedAt'] as const;

export type ProjectSortProperty = (typeof PROJECT_SORTABLE_PROPERTIES)[number];

export type SortDirection = 'asc' | 'desc';

/** Listing filters, mirrored in the URL query params. */
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
