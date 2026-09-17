import { AuthResponse, Role, User } from '../models';

/** Builds a synthetic JWT (fake signature) with the desired `exp`. For tests only. */
export function makeJwt(expiresInSeconds: number): string {
  const payload = { sub: 'ana@empresa.com', exp: Math.floor(Date.now() / 1000) + expiresInSeconds };
  return `header.${btoa(JSON.stringify(payload))}.signature`;
}

/**
 * The refresh token is opaque to the frontend: nothing decodes it, so any string will do.
 * The suffix keeps the assertions readable when a test compares before and after a
 * refresh.
 */
export function makeRefreshToken(suffix = '1'): string {
  return `refresh-token-${suffix}`;
}

export function makeUser(role: Role = 'ADMIN'): User {
  return {
    id: 1,
    name: 'Ana Souza',
    email: 'ana@empresa.com',
    role,
    active: true,
    companyId: 10,
    companyName: 'Empresa Teste',
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
  };
}

export function makeAuthResponse(
  role: Role = 'ADMIN',
  expiresInSeconds = 3600,
  refreshToken = makeRefreshToken(),
): AuthResponse {
  return {
    accessToken: makeJwt(expiresInSeconds),
    refreshToken,
    tokenType: 'Bearer',
    expiresIn: expiresInSeconds,
    user: makeUser(role),
  };
}
