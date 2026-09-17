export type Role = 'ADMIN' | 'ANALYST' | 'DEVELOPER' | 'VIEWER';

export interface User {
  id: number;
  name: string;
  email: string;
  role: Role;
  active: boolean;
  companyId: number;
  companyName: string;
  lastLoginAt: string | null;
  createdAt: string;
}

/** Portuguese labels for the roles, used in the toolbar and in the administration screens. */
export const ROLE_LABELS: Readonly<Record<Role, string>> = {
  ADMIN: 'Administrador',
  ANALYST: 'Analista',
  DEVELOPER: 'Desenvolvedor',
  VIEWER: 'Leitor',
};
