import { Role } from '../../../core/models';

/**
 * Filtros aceitos por `GET /users`. Nenhum deles é usado pela listagem administrativa,
 * que filtra no cliente (ver `UserListComponent`); existem porque o endpoint os aceita e
 * porque o tipo documenta o contrato inteiro em um lugar só.
 */
export interface UserQuery {
  role?: Role;
  active?: boolean;
  search?: string;
}

/**
 * Só o nome. O e-mail não é editável por decisão de segurança do backend: ele é ao mesmo
 * tempo login e canal de recuperação de senha, e um ADMIN capaz de repontá-lo poderia
 * assumir a conta de outra pessoa. Papel e situação têm endpoint próprio, cada um com as
 * suas guardas.
 */
export interface UserUpdateRequest {
  name: string;
}

export const ASSIGNABLE_ROLES: readonly Role[] = ['ADMIN', 'ANALYST', 'DEVELOPER', 'VIEWER'];
