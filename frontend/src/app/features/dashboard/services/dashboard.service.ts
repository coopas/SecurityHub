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
 * Os quatro endpoints de leitura do dashboard, um por região da tela. Cada chamada é
 * independente de propósito: uma falha na tendência não pode apagar os cards, então nada
 * aqui combina as respostas em um único `forkJoin`.
 *
 * Não existe um quinto método para "itens recentes": aquele painel é
 * `GET /vulnerabilities?page=0&size=5&sort=createdAt,desc` e usa o `VulnerabilityService`
 * que já existe.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly baseUrl = `${environment.apiUrl}/dashboard`;

  constructor(private readonly http: HttpClient) {}

  summary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.baseUrl}/summary`);
  }

  /** Array puro com as quatro severidades, inclusive as zeradas, na ordem do enum. */
  severityDistribution(): Observable<SeverityDistributionEntry[]> {
    return this.http.get<SeverityDistributionEntry[]>(`${this.baseUrl}/severity-distribution`);
  }

  /** Array puro com os quatro status, inclusive os zerados, na ordem do enum. */
  statusDistribution(): Observable<StatusDistributionEntry[]> {
    return this.http.get<StatusDistributionEntry[]>(`${this.baseUrl}/status-distribution`);
  }

  /**
   * `days` viaja como pedido: o backend limita a [1, 90] sem erro e devolve o valor
   * efetivo, que é o que a tela rotula. Repetir a validação aqui só criaria duas regras
   * para o mesmo limite.
   */
  trend(days: number): Observable<Trend> {
    const params = new HttpParams().set('days', String(days));
    return this.http.get<Trend>(`${this.baseUrl}/trend`, { params });
  }
}
