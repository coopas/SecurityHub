import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { Comment, CommentRequest } from '../models/comment.model';

/** Comentários vivem sob a vulnerabilidade; a API não expõe exclusão. */
@Injectable({ providedIn: 'root' })
export class CommentService {
  private readonly baseUrl = `${environment.apiUrl}/vulnerabilities`;

  constructor(private readonly http: HttpClient) {}

  /** O backend já devolve do mais antigo para o mais novo, que é como se lê uma discussão. */
  list(vulnerabilityId: number, page: number, size: number): Observable<PageResponse<Comment>> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<PageResponse<Comment>>(this.commentsUrl(vulnerabilityId), { params });
  }

  create(vulnerabilityId: number, request: CommentRequest): Observable<Comment> {
    return this.http.post<Comment>(this.commentsUrl(vulnerabilityId), request);
  }

  update(vulnerabilityId: number, commentId: number, request: CommentRequest): Observable<Comment> {
    return this.http.put<Comment>(`${this.commentsUrl(vulnerabilityId)}/${commentId}`, request);
  }

  private commentsUrl(vulnerabilityId: number): string {
    return `${this.baseUrl}/${vulnerabilityId}/comments`;
  }
}
