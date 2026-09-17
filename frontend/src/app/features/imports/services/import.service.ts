import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import {
  ScanFinding,
  ScanFormat,
  ScanImport,
  ScanImportQuery,
  ScanImportSummary,
} from '../models/scan-import.model';

/**
 * Import of scan reports. The resource is a waiting area: the upload creates a `PENDING`
 * import with the findings already normalized, the review happens on the preview screen
 * and only `confirm` turns the findings into vulnerabilities.
 *
 * The listing returns paginated summaries; the detail is the only place that brings the
 * findings, because a large scan does not fit in a history row.
 */
@Injectable({ providedIn: 'root' })
export class ImportService {
  private readonly baseUrl = `${environment.apiUrl}/scan-imports`;

  constructor(private readonly http: HttpClient) {}

  /** Paginated history; `page`, `size` and `sort` come from the screen's query params. */
  list(query: ScanImportQuery): Observable<PageResponse<ScanImportSummary>> {
    const params = new HttpParams()
      .set('page', String(query.page))
      .set('size', String(query.size))
      .set('sort', query.sort);

    return this.http.get<PageResponse<ScanImportSummary>>(this.baseUrl, { params });
  }

  get(id: number): Observable<ScanImport> {
    return this.http.get<ScanImport>(`${this.baseUrl}/${id}`);
  }

  /**
   * Sends the report as `multipart/form-data`, in the `file`, `projectId` and `format`
   * parts the backend expects.
   *
   * No `Content-Type` is set here, and that is deliberate: the one who builds that
   * header is the browser, because only it knows the boundary that separates the parts.
   * Writing it by hand produces a `multipart/form-data` with no boundary, the server
   * cannot separate a single part and answers 400 or 415 — an error that looks like a
   * backend defect and whose cause lies entirely in this line.
   *
   * `observe: 'events'` with `reportProgress: true` returns the upload progress events,
   * which is what feeds the determinate bar on the screen; the last event is the
   * response with the created import.
   */
  upload(projectId: number, format: ScanFormat, file: File): Observable<HttpEvent<ScanImport>> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    formData.append('projectId', String(projectId));
    formData.append('format', format);

    return this.http.post<ScanImport>(this.baseUrl, formData, {
      reportProgress: true,
      observe: 'events',
    });
  }

  /**
   * Links a finding with no asset to the chosen asset. The response is the whole finding
   * already reclassified by the server — which may return `DUPLICATE` instead of
   * `MATCHED` —, so the row is replaced by what comes back, not by what the screen
   * assumed.
   */
  mapFinding(id: number, findingId: number, assetId: number): Observable<ScanFinding> {
    return this.http.patch<ScanFinding>(`${this.baseUrl}/${id}/findings/${findingId}`, { assetId });
  }

  /** Creates vulnerabilities from the reviewed findings; refuses with `CONFLICT` if repeated. */
  confirm(id: number): Observable<ScanImport> {
    return this.http.post<ScanImport>(`${this.baseUrl}/${id}/confirm`, {});
  }

  /** Discards the whole import. Nothing was created yet, so there is nothing to undo. */
  discard(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
