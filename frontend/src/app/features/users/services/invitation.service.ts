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
 * One service for the whole resource, even though it serves two audiences: `create`, `list`
 * and `revoke` require ADMIN and are consumed by the user administration; `preview` and
 * `accept` are public and come from the accept screen, in `features/auth`. Splitting them
 * into two services would duplicate the base URL and hide that they are the same resource —
 * the boundary that matters is the backend's, and there it is explicit.
 */
@Injectable({ providedIn: 'root' })
export class InvitationService {
  private readonly baseUrl = `${environment.apiUrl}/invitations`;

  constructor(private readonly http: HttpClient) {}

  /** Lists every invitation of the company, in any status. */
  list(): Observable<Invitation[]> {
    return this.http.get<Invitation[]>(this.baseUrl);
  }

  create(request: InvitationRequest): Observable<Invitation> {
    return this.http.post<Invitation>(this.baseUrl, request);
  }

  revoke(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Public: whoever holds the token from the e-mail has proved the invitation is theirs. */
  preview(token: string): Observable<InvitationPreview> {
    const params = new HttpParams().set('token', token);
    return this.http.get<InvitationPreview>(`${this.baseUrl}/accept`, { params });
  }

  /** Creates the account and returns a ready session; the caller is the one that stores it. */
  accept(request: InvitationAcceptRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.baseUrl}/accept`, request);
  }
}
