import { Role } from '../../../core/models';

/**
 * Filters accepted by `GET /users`. None of them is used by the administrative listing,
 * which filters on the client (see `UserListComponent`); they exist because the endpoint
 * accepts them and because the type documents the whole contract in a single place.
 */
export interface UserQuery {
  role?: Role;
  active?: boolean;
  search?: string;
}

/**
 * The name only. The e-mail is not editable, by a security decision on the backend: it is
 * at once the login and the password recovery channel, and an ADMIN able to repoint it
 * could take over someone else's account. Role and status have their own endpoint, each
 * with its own guards.
 */
export interface UserUpdateRequest {
  name: string;
}

export const ASSIGNABLE_ROLES: readonly Role[] = ['ADMIN', 'ANALYST', 'DEVELOPER', 'VIEWER'];
