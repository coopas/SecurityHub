export type AssetType = 'API' | 'SERVER' | 'WEBSITE' | 'DATABASE' | 'WORKSTATION' | 'OTHER';

export type Environment = 'PRODUCTION' | 'STAGING' | 'DEVELOPMENT' | 'TEST';

export type Criticality = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** Asset returned by the API. The backend omits null fields, hence the optionals. */
export interface Asset {
  id: number;
  name: string;
  description?: string;
  type: AssetType;
  identifier?: string;
  environment: Environment;
  criticality: Criticality;
  projectId: number;
  projectName: string;
  /** Always 0 until the vulnerabilities module fills in the real count. */
  vulnerabilityCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AssetRequest {
  projectId: number;
  name: string;
  description?: string;
  type: AssetType;
  identifier?: string;
  environment: Environment;
  criticality: Criticality;
}

/** Mirrors the backend's `AssetService.SORTABLE_PROPERTIES`; the rest is discarded there. */
export const ASSET_SORTABLE_PROPERTIES = [
  'name',
  'type',
  'environment',
  'criticality',
  'createdAt',
  'updatedAt',
] as const;

export type AssetSortProperty = (typeof ASSET_SORTABLE_PROPERTIES)[number];

/** Listing filters, mirrored in the URL query params. */
export interface AssetQuery {
  page: number;
  size: number;
  sort: string;
  search?: string;
  projectId?: number;
  type?: AssetType;
  environment?: Environment;
  criticality?: Criticality;
}

export const ASSET_TYPES: readonly AssetType[] = [
  'API',
  'SERVER',
  'WEBSITE',
  'DATABASE',
  'WORKSTATION',
  'OTHER',
];

export const ENVIRONMENTS: readonly Environment[] = [
  'PRODUCTION',
  'STAGING',
  'DEVELOPMENT',
  'TEST',
];

export const CRITICALITIES: readonly Criticality[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export const ASSET_TYPE_LABELS: Readonly<Record<AssetType, string>> = {
  API: 'API',
  SERVER: 'Servidor',
  WEBSITE: 'Site',
  DATABASE: 'Banco de dados',
  WORKSTATION: 'Estação de trabalho',
  OTHER: 'Outro',
};

export const ENVIRONMENT_LABELS: Readonly<Record<Environment, string>> = {
  PRODUCTION: 'Produção',
  STAGING: 'Homologação',
  DEVELOPMENT: 'Desenvolvimento',
  TEST: 'Teste',
};

export const CRITICALITY_LABELS: Readonly<Record<Criticality, string>> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
  CRITICAL: 'Crítica',
};

/**
 * Icons always go along with the text label: criticality and environment are never
 * communicated by color alone (WCAG 1.4.1).
 */
export const ASSET_TYPE_ICONS: Readonly<Record<AssetType, string>> = {
  API: 'settings_ethernet',
  SERVER: 'dns',
  WEBSITE: 'language',
  DATABASE: 'storage',
  WORKSTATION: 'computer',
  OTHER: 'category',
};

export const ENVIRONMENT_ICONS: Readonly<Record<Environment, string>> = {
  PRODUCTION: 'public',
  STAGING: 'layers',
  DEVELOPMENT: 'code',
  TEST: 'bug_report',
};

export const CRITICALITY_ICONS: Readonly<Record<Criticality, string>> = {
  LOW: 'arrow_downward',
  MEDIUM: 'remove',
  HIGH: 'arrow_upward',
  CRITICAL: 'priority_high',
};

/** Minimal option for the project selector, built from the `ProjectService`. */
export interface ProjectOption {
  id: number;
  name: string;
}

/**
 * Single page of projects loaded for the selector and for the filter. The backend caps
 * `size` at 100; companies with more projects than that still see the project of the asset
 * being edited, which is appended to the list when it does not come in this page.
 */
export const PROJECT_OPTIONS_PAGE_SIZE = 100;
