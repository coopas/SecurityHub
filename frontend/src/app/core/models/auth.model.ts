import { User } from './user.model';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  companyName: string;
  name: string;
  email: string;
  password: string;
}

/**
 * Espelha `AuthResponse` do backend. `refreshToken` é obrigatório: toda resposta que
 * abre sessão (login, cadastro, renovação e aceite de convite) traz o par completo, e
 * deixá-lo opcional faria o modo estrito aceitar em silêncio uma sessão que nasceria
 * sem como se renovar.
 */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  /** Validade do token em segundos. */
  expiresIn: number;
  user: User;
}

/** Corpo de `POST /auth/refresh` e de `POST /auth/logout`: o mesmo DTO nos dois. */
export interface RefreshTokenRequest {
  refreshToken: string;
}

export interface PasswordResetRequest {
  email: string;
}

export interface PasswordResetConfirmRequest {
  token: string;
  password: string;
}

/** Tamanho da senha aceito pelo backend em cadastro, redefinição e aceite de convite. */
export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 100;
