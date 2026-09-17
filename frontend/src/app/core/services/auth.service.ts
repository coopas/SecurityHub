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

  /** The current authenticated user; emits `null` when there is no session. */
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

  /** Opaque to the frontend: it only travels back to the backend, never decoded here. */
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
   * Trades the refresh token for a new pair. Called by the `ErrorInterceptor` in the face
   * of a 401, and by nobody else: the screen does not know the refresh exists.
   */
  refresh(): Observable<AuthResponse> {
    const refreshToken = this.refreshToken;
    if (!refreshToken) {
      // Never reaches the server: without the token the call would be a guaranteed 400,
      // and the interceptor already decides beforehand whether it is worth trying.
      return throwError(() => new Error('Sessão sem refresh token para renovar.'));
    }

    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/refresh`, { refreshToken })
      .pipe(tap((response) => this.storeSession(response)));
  }

  /** Reloads the authenticated user from the backend. */
  me(): Observable<User> {
    return this.http
      .get<User>(`${environment.apiUrl}/auth/me`)
      .pipe(tap((user) => this.storeUser(user)));
  }

  /**
   * Ends the session on both sides. The local cleanup is synchronous and happens before the
   * call: it does not depend on the network, and a server that is down cannot be a reason
   * for the user to stay with a session open in their own browser. The Observable carries
   * only the remote revocation of the refresh token — and, being cold, it needs the caller
   * to subscribe for it to happen.
   *
   * A failure of the revocation is swallowed on purpose: the local refresh token no longer
   * exists, and the row on the server expires by itself.
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
   * Purely local cleanup, no network. It is what the `ErrorInterceptor` uses when the
   * refresh fails: at that point the refresh token is already dead and calling
   * `/auth/logout` with it would only produce one more error.
   */
  clearSession(): void {
    this.clearStorage();
    this.currentUserSubject.next(null);
  }

  requestPasswordReset(request: PasswordResetRequest): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/password-reset/request`, request);
  }

  /** Opens no session: the backend answers 204 and the screen sends the user to the login. */
  confirmPasswordReset(request: PasswordResetConfirmRequest): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/password-reset/confirm`, request);
  }

  /**
   * Stores the session returned by any flow that creates one. Public because invitation
   * acceptance also gives birth to a session and lives in the invitations service, outside
   * here.
   */
  storeSession(response: AuthResponse): void {
    this.writeStorage(ACCESS_TOKEN_STORAGE_KEY, response.accessToken);
    this.writeStorage(REFRESH_TOKEN_STORAGE_KEY, response.refreshToken);
    this.storeUser(response.user);
  }

  /**
   * An expired access token is no longer the end of the session: with a refresh token
   * stored it is still recoverable, and failing here would send to the login someone who
   * only needed a refresh — which the `ErrorInterceptor` would do on the first 401.
   *
   * This terminates. Whoever has only an already dead refresh token is accepted once, the
   * first call comes back 401, the refresh fails and the `ErrorInterceptor` calls
   * `clearSession()`, which wipes the refresh token too. From the second time on there is
   * nothing left in storage, `isAuthenticated()` answers `false` and `/login` holds. The
   * loop closes precisely because the cleanup takes all three keys, and not just the
   * access token.
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
   * Restores the session from local storage. An expired access token is kept when there is
   * a refresh token — the same rule as `isAuthenticated()`, for the same reason.
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

  /** A malformed token is treated as the absence of a session. */
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
      // Storage unavailable (private mode): the session lives in memory only.
    }
  }

  private clearStorage(): void {
    try {
      localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
      localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
      localStorage.removeItem(CURRENT_USER_STORAGE_KEY);
    } catch {
      // Nothing to clear when storage is not available.
    }
  }
}
