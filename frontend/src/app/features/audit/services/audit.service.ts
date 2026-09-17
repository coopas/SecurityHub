import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { AuditLog, AuditQuery } from '../models/audit.model';
import { endOfDayInstant, startOfDayInstant } from '../utils/audit-date.util';

/**
 * Trilha de auditoria. Somente leitura: o recurso é append-only e não existe endpoint
 * por linha para nenhum verbo, então este serviço tem uma única operação de propósito.
 * O backend também exige ADMIN (`@PreAuthorize` em `AuditQueryService.search`).
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

    // A data civil escolhida vira instante aqui, e não no componente: o controller
    // declara `Instant` com `ISO.DATE_TIME`, e o `to` precisa do fim do dia porque a
    // especificação compara com `<=` — a meia-noite esconderia o último dia inteiro.
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
