/**
 * Mirrors the backend's `AuditAction`, in the same order as the Java enum. The screen
 * never writes an action: the value only travels as a filter in the query string.
 */
export type AuditAction =
  | 'LOGIN'
  | 'LOGIN_FAILED'
  | 'LOGOUT'
  | 'TOKEN_REUSE_DETECTED'
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
  'LOGOUT',
  'TOKEN_REUSE_DETECTED',
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
  LOGOUT: 'Logout',
  TOKEN_REUSE_DETECTED: 'Reuso de token detectado',
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
 * An icon always goes with the text label. In an audit trail the action is the most
 * consulted piece of information in the row and it can never depend on color alone
 * (WCAG 1.4.1).
 */
export const AUDIT_ACTION_ICONS: Readonly<Record<AuditAction, string>> = {
  LOGIN: 'login',
  LOGIN_FAILED: 'gpp_bad',
  LOGOUT: 'logout',
  TOKEN_REUSE_DETECTED: 'gpp_maybe',
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
 * `entityType` is free text in the column, not an enum: the filter offers the types the
 * backend services actually record today (the `ENTITY_TYPE` of each `*Service`, plus
 * `User` and `Company`, used by the authentication events). An unknown type coming from
 * the API is still displayed, just without a translation.
 */
export const AUDIT_ENTITY_TYPES: readonly string[] = [
  'Project',
  'Asset',
  'Vulnerability',
  'Comment',
  'User',
  'Invitation',
  'Company',
];

export const AUDIT_ENTITY_TYPE_LABELS: Readonly<Record<string, string>> = {
  Project: 'Projeto',
  Asset: 'Ativo',
  Vulnerability: 'Vulnerabilidade',
  Comment: 'Comentário',
  User: 'Usuário',
  Invitation: 'Convite',
  Company: 'Empresa',
};

/**
 * Content of `oldValue`/`newValue`. The backend serializes `Map<String, Object>`, so it
 * arrives as a JSON object already deserialized by the `HttpClient`; the type accepts
 * `string` because the trail stores raw text and a truncated row or one in an old format
 * cannot bring the screen down — see `readAuditSide`.
 */
export type AuditValues = Record<string, unknown>;

/**
 * Mirrors `AuditLogResponse`. The nullable fields are optional because the backend uses
 * `default-property-inclusion: non_null`: what was null simply does not come.
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

/** Mirrors `AuditQueryService.SORTABLE`; the rest is discarded on the server. */
export const AUDIT_SORTABLE_PROPERTIES = ['createdAt', 'action', 'entityType'] as const;

export type AuditSortProperty = (typeof AUDIT_SORTABLE_PROPERTIES)[number];

/** Mirrors `AuditQueryService.DEFAULT_SORT`. */
export const AUDIT_DEFAULT_SORT = 'createdAt,desc';

/**
 * Trail filters, mirrored in the URL query params. `from` and `to` are civil dates
 * (`yyyy-MM-dd`), the way the user picks them in the calendar and the way the URL stays
 * readable; the conversion to an ISO instant happens in the `AuditService`.
 *
 * There is no `search`: `AuditController` accepts only entityType, actorId, action, from
 * and to, and an invented param would be silently ignored.
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

/** Option of the actor filter. */
export interface AuditActorOption {
  id: number;
  name: string;
  email: string;
  active: boolean;
}

/**
 * Labels for the keys recorded by the `snapshot(...)` of each backend service.
 * A key outside this list is displayed with its own name, without breaking the screen.
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
