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
 * Mirrors the backend's `AuthResponse`. `refreshToken` is required: every response that
 * opens a session (login, sign-up, refresh and invitation acceptance) carries the full
 * pair, and leaving it optional would make strict mode silently accept a session born
 * with no way to refresh itself.
 */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  /** Token lifetime in seconds. */
  expiresIn: number;
  user: User;
}

/** Body of `POST /auth/refresh` and of `POST /auth/logout`: the same DTO in both. */
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

/** Password length accepted by the backend on sign-up, reset and invitation acceptance. */
export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 100;
