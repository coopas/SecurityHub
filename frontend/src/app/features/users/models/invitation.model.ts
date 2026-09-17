import { Role } from '../../../core/models';

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED';

/**
 * Mirrors `InvitationResponse`. It never carries the token nor its hash — the
 * administrative listing does not need the secret, and returning it would hand any
 * invitee's account to whoever opened the screen.
 *
 * The nullable fields are optional because the backend uses `non_null`: an invitation that
 * is still pending simply has no `acceptedAt`.
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

/** The company never travels in the body: it is the authenticated user's. */
export interface InvitationRequest {
  name: string;
  email: string;
  role: Role;
}

/** Public preview: the minimum for the accept screen to say where the invitation leads. */
export interface InvitationPreview {
  name: string;
  email: string;
  companyName: string;
  role: Role;
}

/** Name, e-mail, role and company come from the invitation; the invitee only picks a password. */
export interface InvitationAcceptRequest {
  token: string;
  password: string;
}

export const INVITATION_STATUS_LABELS: Readonly<Record<InvitationStatus, string>> = {
  PENDING: 'Pendente',
  ACCEPTED: 'Aceito',
  REVOKED: 'Revogado',
};
