import { Role, User } from '../../../core/models';
import { Invitation, InvitationPreview } from '../models/invitation.model';

/** Fixtures dos testes; nenhuma tela usa dados simulados. */
export function makeAdminUser(overrides: Partial<User> = {}): User {
  return {
    id: 1,
    name: 'Ana Souza',
    email: 'ana@empresa.com',
    role: 'ADMIN',
    active: true,
    companyId: 10,
    companyName: 'Empresa Teste',
    lastLoginAt: '2026-09-16T08:00:00Z',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

export function makeCompanyUser(
  id: number,
  role: Role = 'ANALYST',
  overrides: Partial<User> = {},
): User {
  return makeAdminUser({
    id,
    name: `Usuário ${id}`,
    email: `usuario${id}@empresa.com`,
    role,
    ...overrides,
  });
}

export function makeInvitation(overrides: Partial<Invitation> = {}): Invitation {
  return {
    id: 5,
    name: 'Bruno Lima',
    email: 'bruno@empresa.com',
    role: 'ANALYST',
    status: 'PENDING',
    expiresAt: '2026-09-24T12:00:00Z',
    invitedById: 1,
    invitedByName: 'Ana Souza',
    createdAt: '2026-09-17T12:00:00Z',
    ...overrides,
  };
}

export function makeInvitationPreview(overrides: Partial<InvitationPreview> = {}): InvitationPreview {
  return {
    name: 'Bruno Lima',
    email: 'bruno@empresa.com',
    companyName: 'Empresa Teste',
    role: 'ANALYST',
    ...overrides,
  };
}
