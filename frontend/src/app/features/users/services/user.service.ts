import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { Role, User } from '../../../core/models';
import { UserQuery, UserUpdateRequest } from '../models/user-admin.model';

/**
 * `GET /users` returns a plain array, not a page: the list is the one for the authenticated
 * user's company and the backend sorts it by name. Wrapping it in `PageResponse` would also
 * break the other two consumers, which already read it as an array.
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

  /** Ends the user's sessions on the server: the role travels inside the access token. */
  changeRole(id: number, role: Role): Observable<User> {
    return this.http.patch<User>(`${this.baseUrl}/${id}/role`, { role });
  }

  /** Deactivating ends the sessions; reactivating has nothing to end. */
  changeActive(id: number, active: boolean): Observable<User> {
    return this.http.patch<User>(`${this.baseUrl}/${id}/active`, { active });
  }
}
