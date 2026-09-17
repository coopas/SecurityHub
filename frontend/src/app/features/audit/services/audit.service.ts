import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { AuditLog, AuditQuery } from '../models/audit.model';
import { endOfDayInstant, startOfDayInstant } from '../utils/audit-date.util';

/**
 * Audit trail. Read-only: the resource is append-only and there is no per-row endpoint
 * for any verb, so this service has a single operation on purpose. The backend also
 * requires ADMIN (`@PreAuthorize` on `AuditQueryService.search`).
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly baseUrl = `${environment.apiUrl}/audit-logs`;

  constructor(private readonly http: HttpClient) {}

  list(query: AuditQuery): Observable<PageResponse<AuditLog>> {
    let params = new HttpParams()
      .set('page', String(query.page))
      .set('size', String(query.size))
      .set('sort', query.sort);

    if (query.entityType) {
      params = params.set('entityType', query.entityType);
    }
    if (query.actorId) {
      params = params.set('actorId', String(query.actorId));
    }
    if (query.action) {
      params = params.set('action', query.action);
    }

    // The chosen civil date becomes an instant here, and not in the component: the
    // controller declares `Instant` with `ISO.DATE_TIME`, and `to` needs the end of the
    // day because the specification compares with `<=` — midnight would hide the whole
    // last day.
    const from = query.from ? startOfDayInstant(query.from) : null;
    if (from) {
      params = params.set('from', from);
    }
    const to = query.to ? endOfDayInstant(query.to) : null;
    if (to) {
      params = params.set('to', to);
    }

    return this.http.get<PageResponse<AuditLog>>(this.baseUrl, { params });
  }
}
