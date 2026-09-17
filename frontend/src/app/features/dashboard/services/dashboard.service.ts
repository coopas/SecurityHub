import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import {
  DashboardSummary,
  SeverityDistributionEntry,
  StatusDistributionEntry,
  Trend,
} from '../models/dashboard.model';

/**
 * The dashboard's four read endpoints, one per region of the screen. Each call is
 * independent on purpose: a failure in the trend cannot wipe out the cards, so nothing here
 * combines the responses into a single `forkJoin`.
 *
 * There is no fifth method for "itens recentes": that panel is
 * `GET /vulnerabilities?page=0&size=5&sort=createdAt,desc` and uses the
 * `VulnerabilityService` that already exists.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly baseUrl = `${environment.apiUrl}/dashboard`;

  constructor(private readonly http: HttpClient) {}

  summary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.baseUrl}/summary`);
  }

  /** Plain array with the four severities, the zeroed ones included, in enum order. */
  severityDistribution(): Observable<SeverityDistributionEntry[]> {
    return this.http.get<SeverityDistributionEntry[]>(`${this.baseUrl}/severity-distribution`);
  }

  /** Plain array with the four statuses, the zeroed ones included, in enum order. */
  statusDistribution(): Observable<StatusDistributionEntry[]> {
    return this.http.get<StatusDistributionEntry[]>(`${this.baseUrl}/status-distribution`);
  }

  /**
   * `days` travels exactly as asked: the backend clamps it to [1, 90] without an error and
   * returns the effective value, which is what the screen labels. Repeating the validation
   * here would only create two rules for the same limit.
   */
  trend(days: number): Observable<Trend> {
    const params = new HttpParams().set('days', String(days));
    return this.http.get<Trend>(`${this.baseUrl}/trend`, { params });
  }
}
