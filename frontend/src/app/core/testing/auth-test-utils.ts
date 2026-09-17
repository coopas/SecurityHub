import { AuthResponse, Role, User } from '../models';

/** Monta um JWT sintético (assinatura fictícia) com o `exp` desejado. Apenas para testes. */
export function makeJwt(expiresInSeconds: number): string {
  const payload = { sub: 'ana@empresa.com', exp: Math.floor(Date.now() / 1000) + expiresInSeconds };
  return `header.${btoa(JSON.stringify(payload))}.signature`;
}

/**
 * O refresh token é opaco para o frontend: nada o decodifica, então uma string qualquer
 * basta. O sufixo torna as asserções legíveis quando um teste compara antes e depois de
 * uma renovação.
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
