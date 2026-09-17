import { HttpClient, HttpEvent, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { Attachment } from '../models/attachment.model';

/**
 * Attachments live under the vulnerability: no operation exists without the parent's id.
 *
 * The listing returns a plain array, not the paginated envelope: the server caps it at
 * `MAX_ATTACHMENTS` per vulnerability, so there is never a second page.
 */
@Injectable({ providedIn: 'root' })
export class AttachmentService {
  private readonly baseUrl = `${environment.apiUrl}/vulnerabilities`;

  constructor(private readonly http: HttpClient) {}

  list(vulnerabilityId: number): Observable<Attachment[]> {
    return this.http.get<Attachment[]>(this.attachmentsUrl(vulnerabilityId));
  }

  /**
   * Uploads the file as `multipart/form-data`, in the `file` part that the backend expects.
   *
   * No `Content-Type` is set here, and that is deliberate: the one who assembles that
   * header is the browser, because only it knows the boundary that separates the parts.
   * Writing it by hand produces a `multipart/form-data` with no boundary, the server
   * cannot separate a single part and answers 400 or 415 — an error that looks like a
   * backend defect and whose cause lies entirely in this line.
   *
   * `observe: 'events'` with `reportProgress: true` returns the upload progress events,
   * which is what feeds the screen's determinate bar; the last event is the response with
   * the created attachment.
   */
  upload(vulnerabilityId: number, file: File): Observable<HttpEvent<Attachment>> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    return this.http.post<Attachment>(this.attachmentsUrl(vulnerabilityId), formData, {
      reportProgress: true,
      observe: 'events',
    });
  }

  /**
   * `observe: 'response'` is not a preference: the file name comes in the
   * `Content-Disposition`, and only the whole response gives access to the headers.
   */
  download(vulnerabilityId: number, attachmentId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.attachmentsUrl(vulnerabilityId)}/${attachmentId}/download`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  delete(vulnerabilityId: number, attachmentId: number): Observable<void> {
    return this.http.delete<void>(`${this.attachmentsUrl(vulnerabilityId)}/${attachmentId}`);
  }

  private attachmentsUrl(vulnerabilityId: number): string {
    return `${this.baseUrl}/${vulnerabilityId}/attachments`;
  }
}
