import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { AuthResponse } from '../../../core/models';
import {
  Invitation,
  InvitationAcceptRequest,
  InvitationPreview,
  InvitationRequest,
} from '../models/invitation.model';

/**
 * Um serviço para o recurso inteiro, embora ele atenda duas plateias: `create`, `list` e
 * `revoke` exigem ADMIN e são consumidos pela administração de usuários; `preview` e
 * `accept` são públicos e vêm da tela de aceite, em `features/auth`. Separá-los em dois
 * serviços duplicaria a URL base e esconderia que são o mesmo recurso — a fronteira que
 * importa é a do backend, e lá ela está explícita.
 */
@Injectable({ providedIn: 'root' })
export class InvitationService {
  private readonly baseUrl = `${environment.apiUrl}/invitations`;

  constructor(private readonly http: HttpClient) {}

  /** Lista todos os convites da empresa, em qualquer situação. */
  list(): Observable<Invitation[]> {
    return this.http.get<Invitation[]>(this.baseUrl);
  }

  create(request: InvitationRequest): Observable<Invitation> {
    return this.http.post<Invitation>(this.baseUrl, request);
  }

  revoke(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Público: quem tem o token do e-mail já provou que o convite é dele. */
  preview(token: string): Observable<InvitationPreview> {
    const params = new HttpParams().set('token', token);
    return this.http.get<InvitationPreview>(`${this.baseUrl}/accept`, { params });
  }

  /** Cria a conta e devolve a sessão pronta; quem chama é que a guarda. */
  accept(request: InvitationAcceptRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.baseUrl}/accept`, request);
  }
}
