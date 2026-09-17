import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { User } from '../../../core/models';
import { AuditActorOption } from '../models/audit.model';

/**
 * Opções do filtro por ator. Ao contrário do seletor de responsável das
 * vulnerabilidades, aqui **não** se filtra por `active=true`: a trilha é histórica e um
 * usuário desativado continua sendo o autor das linhas que ele gerou. Escondê-lo
 * tornaria essas linhas inalcançáveis pelo filtro.
 *
 * `GET /users` é liberado a ADMIN e ANALYST, e esta tela já é exclusiva de ADMIN.
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
