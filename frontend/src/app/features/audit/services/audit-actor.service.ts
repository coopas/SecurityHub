import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { User } from '../../../core/models';
import { AuditActorOption } from '../models/audit.model';

/**
 * Options of the actor filter. Unlike the assignee picker of the vulnerabilities, here we
 * do **not** filter by `active=true`: the trail is historical and a deactivated user is
 * still the author of the rows he generated. Hiding him would make those rows unreachable
 * by the filter.
 *
 * `GET /users` is open to ADMIN and ANALYST, and this screen is already ADMIN-only.
 */
@Injectable({ providedIn: 'root' })
export class AuditActorService {
  private readonly baseUrl = `${environment.apiUrl}/users`;

  constructor(private readonly http: HttpClient) {}

  list(): Observable<AuditActorOption[]> {
    return this.http
      .get<User[]>(this.baseUrl)
      .pipe(
        map((users) => users.map(({ id, name, email, active }) => ({ id, name, email, active }))),
      );
  }
}
