import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, catchError, of, tap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  AuthResponse,
  LoginRequest,
  PasswordResetConfirmRequest,
  PasswordResetRequest,
  RegisterRequest,
  Role,
  User,
} from '../models';

export const ACCESS_TOKEN_STORAGE_KEY = 'securityhub.accessToken';
export const REFRESH_TOKEN_STORAGE_KEY = 'securityhub.refreshToken';
export const CURRENT_USER_STORAGE_KEY = 'securityhub.currentUser';

interface JwtPayload {
  exp?: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly currentUserSubject = new BehaviorSubject<User | null>(null);

  /** Usuário autenticado corrente; emite `null` quando não há sessão. */
  readonly currentUser$: Observable<User | null> = this.currentUserSubject.asObservable();

  constructor(private readonly http: HttpClient) {
    this.restoreSession();
  }

  get currentUser(): User | null {
    return this.currentUserSubject.value;
  }

  get accessToken(): string | null {
    return this.readStorage(ACCESS_TOKEN_STORAGE_KEY);
  }

  /** Opaco para o frontend: só viaja de volta ao backend, nunca é decodificado aqui. */
  get refreshToken(): string | null {
    return this.readStorage(REFRESH_TOKEN_STORAGE_KEY);
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/login`, request)
      .pipe(tap((response) => this.storeSession(response)));
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/register`, request)
      .pipe(tap((response) => this.storeSession(response)));
  }

  /**
   * Troca o refresh token por um par novo. Chamado pelo `ErrorInterceptor` diante de um
   * 401, e por mais ninguém: a tela não sabe que a renovação existe.
   */
  refresh(): Observable<AuthResponse> {
    const refreshToken = this.refreshToken;
    if (!refreshToken) {
      // Não chega ao servidor: sem o token a chamada seria um 400 garantido, e o
      // interceptador já decide antes se vale a pena tentar.
      return throwError(() => new Error('Sessão sem refresh token para renovar.'));
    }

    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/refresh`, { refreshToken })
      .pipe(tap((response) => this.storeSession(response)));
  }

  /** Recarrega o usuário autenticado a partir do backend. */
  me(): Observable<User> {
    return this.http
      .get<User>(`${environment.apiUrl}/auth/me`)
      .pipe(tap((user) => this.storeUser(user)));
  }

  /**
   * Encerra a sessão dos dois lados. A limpeza local é síncrona e acontece antes da
   * chamada: ela não depende da rede, e um servidor fora do ar não pode ser motivo para
   * o usuário continuar com uma sessão aberta no próprio navegador. O Observable carrega
   * apenas a revogação remota do refresh token — e, por ser frio, exige inscrição do
   * chamador para acontecer.
   *
   * Uma falha da revogação é engolida de propósito: o refresh token local já não existe
   * mais, e a linha no servidor expira sozinha.
   */
  logout(): Observable<void> {
    const refreshToken = this.refreshToken;
    this.clearSession();

    if (!refreshToken) {
      return of(void 0);
    }

    return this.http
      .post<void>(`${environment.apiUrl}/auth/logout`, { refreshToken })
      .pipe(catchError(() => of(void 0)));
  }

  /**
   * Limpeza puramente local, sem rede. É o que o `ErrorInterceptor` usa quando a
   * renovação falha: nesse ponto o refresh token já está morto e chamar `/auth/logout`
   * com ele só produziria mais um erro.
   */
  clearSession(): void {
    this.clearStorage();
    this.currentUserSubject.next(null);
  }

  requestPasswordReset(request: PasswordResetRequest): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/password-reset/request`, request);
  }

  /** Não abre sessão: o backend responde 204 e a tela manda o usuário ao login. */
  confirmPasswordReset(request: PasswordResetConfirmRequest): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/password-reset/confirm`, request);
  }

  /**
   * Grava a sessão devolvida por qualquer fluxo que a crie. Público porque o aceite de
   * convite também nasce uma sessão e mora no serviço de convites, fora daqui.
   */
  storeSession(response: AuthResponse): void {
    this.writeStorage(ACCESS_TOKEN_STORAGE_KEY, response.accessToken);
    this.writeStorage(REFRESH_TOKEN_STORAGE_KEY, response.refreshToken);
    this.storeUser(response.user);
  }

  /**
   * Um access token vencido não é mais o fim da sessão: com refresh token guardado ela
   * ainda é recuperável, e reprovar aqui mandaria ao login quem só precisava de uma
   * renovação — que o `ErrorInterceptor` faria no primeiro 401.
   *
   * Isto termina. Quem tem apenas um refresh token já morto é aceito uma vez, a primeira
   * chamada volta 401, a renovação falha e o `ErrorInterceptor` chama `clearSession()`,
   * que apaga também o refresh token. Da segunda vez em diante não há mais nada no
   * armazenamento, `isAuthenticated()` responde `false` e o `/login` fica de pé. O laço
   * fecha justamente porque a limpeza leva as três chaves, e não só o access token.
   */
  isAuthenticated(): boolean {
    const token = this.accessToken;
    if (!token || this.currentUser === null) {
      return false;
    }
    return !this.isTokenExpired(token) || this.refreshToken !== null;
  }

  hasRole(...roles: Role[]): boolean {
    const user = this.currentUser;
    if (!user) {
      return false;
    }
    return roles.length === 0 || roles.includes(user.role);
  }

  private storeUser(user: User): void {
    this.writeStorage(CURRENT_USER_STORAGE_KEY, JSON.stringify(user));
    this.currentUserSubject.next(user);
  }

  /**
   * Restaura a sessão do armazenamento local. Um access token vencido é mantido quando
   * há refresh token — a mesma regra de `isAuthenticated()`, pelo mesmo motivo.
   */
  private restoreSession(): void {
    const token = this.readStorage(ACCESS_TOKEN_STORAGE_KEY);
    const rawUser = this.readStorage(CURRENT_USER_STORAGE_KEY);
    const recoverable = this.readStorage(REFRESH_TOKEN_STORAGE_KEY) !== null;
    if (!token || !rawUser || (this.isTokenExpired(token) && !recoverable)) {
      this.clearStorage();
      return;
    }

    try {
      this.currentUserSubject.next(JSON.parse(rawUser) as User);
    } catch {
      this.clearStorage();
    }
  }

  /** Um token malformado é tratado como ausência de sessão. */
  private isTokenExpired(token: string): boolean {
    const payload = this.decodeToken(token);
    if (!payload || typeof payload.exp !== 'number') {
      return true;
    }
    return payload.exp * 1000 <= Date.now();
  }

  private decodeToken(token: string): JwtPayload | null {
    try {
      const segment = token.split('.')[1];
      if (!segment) {
        return null;
      }
      const normalized = segment.replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(normalized)) as JwtPayload;
    } catch {
      return null;
    }
  }

  private readStorage(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeStorage(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Armazenamento indisponível (modo privado): a sessão vive apenas em memória.
    }
  }

  private clearStorage(): void {
    try {
      localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
      localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
      localStorage.removeItem(CURRENT_USER_STORAGE_KEY);
    } catch {
      // Nada a limpar quando o armazenamento não está disponível.
    }
  }
}
