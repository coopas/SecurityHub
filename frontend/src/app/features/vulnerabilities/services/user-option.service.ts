import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { User } from '../../../core/models';
import { UserSummary } from '../models/vulnerability.model';

/**
 * Opções de responsável. `GET /users` é liberado apenas para ADMIN e ANALYST, então
 * quem não pode atribuir também não deve chamar este serviço — a tela decide antes.
 */
@Injectable({ providedIn: 'root' })
export class UserOptionService {
  private readonly baseUrl = `${environment.apiUrl}/users`;

  constructor(private readonly http: HttpClient) {}

  /** Somente usuários ativos: o backend recusa atribuir a um usuário desativado. */
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
