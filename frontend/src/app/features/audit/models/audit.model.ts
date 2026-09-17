/**
 * Espelha `AuditAction` do backend, na mesma ordem do enum Java. A tela nunca escreve
 * uma ação: o valor só viaja como filtro na query string.
 */
export type AuditAction =
  | 'LOGIN'
  | 'LOGIN_FAILED'
  | 'REGISTER'
  | 'CREATE'
  | 'UPDATE'
  | 'DELETE'
  | 'STATUS_CHANGE'
  | 'ASSIGN'
  | 'COMMENT'
  | 'PASSWORD_RESET'
  | 'USER_INVITED'
  | 'USER_UPDATED'
  | 'EXPORT'
  | 'SCAN_IMPORT';

export const AUDIT_ACTIONS: readonly AuditAction[] = [
  'LOGIN',
  'LOGIN_FAILED',
  'REGISTER',
  'CREATE',
  'UPDATE',
  'DELETE',
  'STATUS_CHANGE',
  'ASSIGN',
  'COMMENT',
  'PASSWORD_RESET',
  'USER_INVITED',
  'USER_UPDATED',
  'EXPORT',
  'SCAN_IMPORT',
];

export const AUDIT_ACTION_LABELS: Readonly<Record<AuditAction, string>> = {
  LOGIN: 'Login',
  LOGIN_FAILED: 'Login recusado',
  REGISTER: 'Cadastro',
  CREATE: 'Criação',
  UPDATE: 'Alteração',
  DELETE: 'Exclusão',
  STATUS_CHANGE: 'Mudança de status',
  ASSIGN: 'Atribuição',
  COMMENT: 'Comentário',
  PASSWORD_RESET: 'Redefinição de senha',
  USER_INVITED: 'Convite de usuário',
  USER_UPDATED: 'Atualização de usuário',
  EXPORT: 'Exportação',
  SCAN_IMPORT: 'Importação de scan',
};

/**
 * Ícone acompanha sempre o rótulo em texto. Numa trilha de auditoria a ação é a
 * informação mais consultada da linha e ela nunca pode depender só da cor (WCAG 1.4.1).
 */
export const AUDIT_ACTION_ICONS: Readonly<Record<AuditAction, string>> = {
  LOGIN: 'login',
  LOGIN_FAILED: 'gpp_bad',
  REGISTER: 'person_add',
  CREATE: 'add_circle_outline',
  UPDATE: 'edit',
  DELETE: 'delete_outline',
  STATUS_CHANGE: 'swap_horiz',
  ASSIGN: 'assignment_ind',
  COMMENT: 'chat_bubble_outline',
  PASSWORD_RESET: 'lock_reset',
  USER_INVITED: 'mail_outline',
  USER_UPDATED: 'manage_accounts',
  EXPORT: 'file_download',
  SCAN_IMPORT: 'upload_file',
};

/**
 * `entityType` é texto livre na coluna, não um enum: o filtro oferece os tipos que os
 * serviços do backend realmente gravam hoje (`ENTITY_TYPE` de cada `*Service`, mais
 * `User` e `Company`, usados pelos eventos de autenticação). Um tipo desconhecido
 * vindo da API continua sendo exibido, apenas sem tradução.
 */
export const AUDIT_ENTITY_TYPES: readonly string[] = [
  'Project',
  'Asset',
  'Vulnerability',
  'Comment',
  'User',
  'Company',
];

export const AUDIT_ENTITY_TYPE_LABELS: Readonly<Record<string, string>> = {
  Project: 'Projeto',
  Asset: 'Ativo',
  Vulnerability: 'Vulnerabilidade',
  Comment: 'Comentário',
  User: 'Usuário',
  Company: 'Empresa',
};

/**
 * Conteúdo de `oldValue`/`newValue`. O backend serializa `Map<String, Object>`, então
 * chega como objeto JSON já desserializado pelo `HttpClient`; o tipo aceita `string`
 * porque a trilha guarda texto bruto e uma linha truncada ou de formato antigo não
 * pode derrubar a tela — ver `readAuditSide`.
 */
export type AuditValues = Record<string, unknown>;

/**
 * Espelha `AuditLogResponse`. Os campos anuláveis são opcionais porque o backend usa
 * `default-property-inclusion: non_null`: o que era nulo simplesmente não vem.
 */
export interface AuditLog {
  id: number;
  actorId?: number;
  actorEmail?: string;
  action: AuditAction;
  entityType: string;
  entityId?: number;
  oldValue?: AuditValues | string;
  newValue?: AuditValues | string;
  ipAddress?: string;
  createdAt: string;
}

/** Espelha `AuditQueryService.SORTABLE`; o resto é descartado no servidor. */
export const AUDIT_SORTABLE_PROPERTIES = ['createdAt', 'action', 'entityType'] as const;

export type AuditSortProperty = (typeof AUDIT_SORTABLE_PROPERTIES)[number];

/** Espelha `AuditQueryService.DEFAULT_SORT`. */
export const AUDIT_DEFAULT_SORT = 'createdAt,desc';

/**
 * Filtros da trilha, espelhados nos query params da URL. `from` e `to` são datas
 * civis (`yyyy-MM-dd`), do jeito que o usuário escolhe no calendário e que a URL
 * fica legível; a conversão para instante ISO acontece no `AuditService`.
 *
 * Não existe `search`: `AuditController` aceita apenas entityType, actorId, action,
 * from e to, e um parâmetro inventado seria silenciosamente ignorado.
 */
export interface AuditQuery {
  page: number;
  size: number;
  sort: string;
  entityType?: string;
  actorId?: number;
  action?: AuditAction;
  from?: string;
  to?: string;
}

/** Opção do filtro por ator. */
export interface AuditActorOption {
  id: number;
  name: string;
  email: string;
  active: boolean;
}

/**
 * Rótulos das chaves gravadas pelos `snapshot(...)` de cada serviço do backend.
 * Uma chave fora desta lista é exibida com o próprio nome, sem quebrar a tela.
 */
export const AUDIT_FIELD_LABELS: Readonly<Record<string, string>> = {
  name: 'Nome',
  title: 'Título',
  description: 'Descrição',
  status: 'Status',
  type: 'Tipo',
  identifier: 'Identificador',
  environment: 'Ambiente',
  criticality: 'Criticidade',
  severity: 'Severidade',
  cvssScore: 'Pontuação CVSS',
  cve: 'CVE',
  discoveredAt: 'Descoberta em',
  dueDate: 'Prazo',
  resolvedAt: 'Resolvida em',
  projectId: 'Projeto (id)',
  assetId: 'Ativo (id)',
  assignedToId: 'Responsável (id)',
  assignedToEmail: 'Responsável (e-mail)',
  vulnerabilityId: 'Vulnerabilidade (id)',
  authorId: 'Autor (id)',
  contentLength: 'Tamanho do texto',
};

export function auditFieldLabel(field: string): string {
  return AUDIT_FIELD_LABELS[field] ?? field;
}

export function auditEntityTypeLabel(entityType: string): string {
  return AUDIT_ENTITY_TYPE_LABELS[entityType] ?? entityType;
}
