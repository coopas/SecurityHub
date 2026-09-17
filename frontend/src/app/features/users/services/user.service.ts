import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { Role, User } from '../../../core/models';
import { UserQuery, UserUpdateRequest } from '../models/user-admin.model';

/**
 * `GET /users` devolve um array puro, não uma página: a lista é a da empresa do
 * autenticado e o backend a ordena por nome. Envelopá-la em `PageResponse` quebraria
 * também os outros dois consumidores, que já a leem como array.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly baseUrl = `${environment.apiUrl}/users`;

  constructor(private readonly http: HttpClient) {}

  list(query: UserQuery = {}): Observable<User[]> {
    let params = new HttpParams();
    if (query.role) {
      params = params.set('role', query.role);
    }
    if (query.active !== undefined) {
      params = params.set('active', String(query.active));
    }
    if (query.search) {
      params = params.set('search', query.search);
    }

    return this.http.get<User[]>(this.baseUrl, { params });
  }

  get(id: number): Observable<User> {
    return this.http.get<User>(`${this.baseUrl}/${id}`);
  }

  update(id: number, request: UserUpdateRequest): Observable<User> {
    return this.http.patch<User>(`${this.baseUrl}/${id}`, request);
  }

  /** Encerra as sessões do usuário no servidor: o papel viaja dentro do access token. */
  changeRole(id: number, role: Role): Observable<User> {
    return this.http.patch<User>(`${this.baseUrl}/${id}/role`, { role });
  }

  /** Desativar encerra as sessões; reativar não tem o que encerrar. */
  changeActive(id: number, active: boolean): Observable<User> {
    return this.http.patch<User>(`${this.baseUrl}/${id}/active`, { active });
  }
}
