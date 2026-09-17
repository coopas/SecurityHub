import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { User } from '../../../core/models';
import { UserSummary } from '../models/vulnerability.model';

/**
 * Assignee options. `GET /users` is allowed only for ADMIN and ANALYST, so whoever cannot
 * assign should not call this service either — the screen decides beforehand.
 */
@Injectable({ providedIn: 'root' })
export class UserOptionService {
  private readonly baseUrl = `${environment.apiUrl}/users`;

  constructor(private readonly http: HttpClient) {}

  /** Active users only: the backend refuses to assign to a deactivated user. */
  listActive(): Observable<UserSummary[]> {
    const params = new HttpParams().set('active', 'true');
    return this.http
      .get<User[]>(this.baseUrl, { params })
      .pipe(
        map((users) =>
          users.map(({ id, name, email, role }) => ({ id, name, email, role }) as UserSummary),
        ),
      );
  }
}
