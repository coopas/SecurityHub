import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';

/**
 * The executive report as a PDF. It lives in the dashboard, and not in a `reports` feature
 * of its own, because it is a button: the report's numbers are the same aggregations this
 * screen already shows, and a module with a route, routing and lazy loading for a single
 * call would be structure without content. The day a reports screen exists — with a period
 * picker, history or formats — it takes this service with it.
 */
@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly baseUrl = `${environment.apiUrl}/reports`;

  constructor(private readonly http: HttpClient) {}

  /**
   * No parameters, on purpose: the endpoint does not accept a date range, and every number
   * is from the moment of generation.
   *
   * `observe: 'response'` is not a preference: the file name comes in the
   * `Content-Disposition`, and only the whole response gives access to the headers.
   */
  executive(): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/executive`, {
      responseType: 'blob',
      observe: 'response',
    });
  }
}
