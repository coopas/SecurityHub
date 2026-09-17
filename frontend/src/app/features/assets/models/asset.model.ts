export type AssetType = 'API' | 'SERVER' | 'WEBSITE' | 'DATABASE' | 'WORKSTATION' | 'OTHER';

export type Environment = 'PRODUCTION' | 'STAGING' | 'DEVELOPMENT' | 'TEST';

export type Criticality = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** Ativo retornado pela API. O backend omite campos nulos, daí os opcionais. */
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
  /** Sempre 0 até o módulo de vulnerabilidades preencher a contagem real. */
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

/** Espelha `AssetService.SORTABLE_PROPERTIES` do backend; o resto é descartado lá. */
export const ASSET_SORTABLE_PROPERTIES = [
  'name',
  'type',
  'environment',
  'criticality',
  'createdAt',
  'updatedAt',
] as const;

export type AssetSortProperty = (typeof ASSET_SORTABLE_PROPERTIES)[number];

/** Filtros da listagem, espelhados nos query params da URL. */
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
 * Ícones acompanham sempre o rótulo em texto: criticidade e ambiente nunca são
 * comunicados apenas por cor (WCAG 1.4.1).
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

/** Opção mínima do seletor de projetos, montada a partir do `ProjectService`. */
export interface ProjectOption {
  id: number;
  name: string;
}

/**
 * Página única de projetos carregada para o seletor e para o filtro. O backend limita
 * `size` a 100; empresas com mais projetos que isso ainda enxergam o projeto do ativo
 * em edição, que é acrescentado à lista quando não vem nesta página.
 */
export const PROJECT_OPTIONS_PAGE_SIZE = 100;
