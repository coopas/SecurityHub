import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthResponse, LoginRequest, RegisterRequest, Role, User } from '../models';

export const ACCESS_TOKEN_STORAGE_KEY = 'securityhub.accessToken';
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

  /** Recarrega o usuário autenticado a partir do backend. */
  me(): Observable<User> {
    return this.http
      .get<User>(`${environment.apiUrl}/auth/me`)
      .pipe(tap((user) => this.storeUser(user)));
  }

  logout(): void {
    this.clearStorage();
    this.currentUserSubject.next(null);
  }

  isAuthenticated(): boolean {
    const token = this.accessToken;
    return !!token && !this.isTokenExpired(token) && this.currentUser !== null;
  }

  hasRole(...roles: Role[]): boolean {
    const user = this.currentUser;
    if (!user) {
      return false;
    }
    return roles.length === 0 || roles.includes(user.role);
  }

  private storeSession(response: AuthResponse): void {
    this.writeStorage(ACCESS_TOKEN_STORAGE_KEY, response.accessToken);
    this.storeUser(response.user);
  }

  private storeUser(user: User): void {
    this.writeStorage(CURRENT_USER_STORAGE_KEY, JSON.stringify(user));
    this.currentUserSubject.next(user);
  }

  /** Restaura a sessão do armazenamento local, descartando token expirado ou inválido. */
  private restoreSession(): void {
    const token = this.readStorage(ACCESS_TOKEN_STORAGE_KEY);
    const rawUser = this.readStorage(CURRENT_USER_STORAGE_KEY);
    if (!token || !rawUser || this.isTokenExpired(token)) {
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
      localStorage.removeItem(CURRENT_USER_STORAGE_KEY);
    } catch {
      // Nada a limpar quando o armazenamento não está disponível.
    }
  }
}
