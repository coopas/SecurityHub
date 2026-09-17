import { Role } from '../../../core/models';

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED';

/**
 * Espelha `InvitationResponse`. Nunca traz o token nem o seu hash — a listagem
 * administrativa não precisa do segredo, e devolvê-lo seria entregar a conta de
 * qualquer convidado a quem abrisse a tela.
 *
 * Os campos anuláveis são opcionais porque o backend usa `non_null`: um convite ainda
 * pendente simplesmente não tem `acceptedAt`.
 */
export interface Invitation {
  id: number;
  name: string;
  email: string;
  role: Role;
  status: InvitationStatus;
  expiresAt: string;
  acceptedAt?: string;
  invitedById?: number;
  invitedByName?: string;
  createdAt: string;
}

/** A empresa nunca viaja no corpo: é a do usuário autenticado. */
export interface InvitationRequest {
  name: string;
  email: string;
  role: Role;
}

/** Prévia pública: o mínimo para a tela de aceite dizer para onde o convite leva. */
export interface InvitationPreview {
  name: string;
  email: string;
  companyName: string;
  role: Role;
}

/** Nome, e-mail, papel e empresa vêm do convite; o convidado só escolhe a senha. */
export interface InvitationAcceptRequest {
  token: string;
  password: string;
}

export const INVITATION_STATUS_LABELS: Readonly<Record<InvitationStatus, string>> = {
  PENDING: 'Pendente',
  ACCEPTED: 'Aceito',
  REVOKED: 'Revogado',
};
