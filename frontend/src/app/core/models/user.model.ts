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

/** Rótulos em português para os papéis, usados na toolbar e nas telas de administração. */
export const ROLE_LABELS: Readonly<Record<Role, string>> = {
  ADMIN: 'Administrador',
  ANALYST: 'Analista',
  DEVELOPER: 'Desenvolvedor',
  VIEWER: 'Leitor',
};
