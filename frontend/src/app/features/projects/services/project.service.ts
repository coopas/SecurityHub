import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { Project, ProjectQuery, ProjectRequest } from '../models/project.model';

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly baseUrl = `${environment.apiUrl}/projects`;

  constructor(private readonly http: HttpClient) {}

  /** Lista paginada; `search` e `status` só viajam quando preenchidos. */
  list(query: ProjectQuery): Observable<PageResponse<Project>> {
    let params = new HttpParams()
      .set('page', String(query.page))
      .set('size', String(query.size))
      .set('sort', query.sort);

    if (query.search) {
      params = params.set('search', query.search);
    }
    if (query.status) {
      params = params.set('status', query.status);
    }

    return this.http.get<PageResponse<Project>>(this.baseUrl, { params });
  }

  get(id: number): Observable<Project> {
    return this.http.get<Project>(`${this.baseUrl}/${id}`);
  }

  create(request: ProjectRequest): Observable<Project> {
    return this.http.post<Project>(this.baseUrl, request);
  }

  update(id: number, request: ProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
