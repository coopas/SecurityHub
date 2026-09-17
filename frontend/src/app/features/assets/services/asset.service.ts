import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { Asset, AssetQuery, AssetRequest } from '../models/asset.model';

@Injectable({ providedIn: 'root' })
export class AssetService {
  private readonly baseUrl = `${environment.apiUrl}/assets`;

  constructor(private readonly http: HttpClient) {}

  /** Lista paginada; os filtros opcionais só viajam quando preenchidos. */
  list(query: AssetQuery): Observable<PageResponse<Asset>> {
    let params = new HttpParams()
      .set('page', String(query.page))
      .set('size', String(query.size))
      .set('sort', query.sort);

    if (query.search) {
      params = params.set('search', query.search);
    }
    if (query.projectId) {
      params = params.set('projectId', String(query.projectId));
    }
    if (query.type) {
      params = params.set('type', query.type);
    }
    if (query.environment) {
      params = params.set('environment', query.environment);
    }
    if (query.criticality) {
      params = params.set('criticality', query.criticality);
    }

    return this.http.get<PageResponse<Asset>>(this.baseUrl, { params });
  }

  get(id: number): Observable<Asset> {
    return this.http.get<Asset>(`${this.baseUrl}/${id}`);
  }

  create(request: AssetRequest): Observable<Asset> {
    return this.http.post<Asset>(this.baseUrl, request);
  }

  update(id: number, request: AssetRequest): Observable<Asset> {
    return this.http.put<Asset>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
