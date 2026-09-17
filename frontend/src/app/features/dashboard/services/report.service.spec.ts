import { HttpResponse } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { ReportService } from './report.service';

describe('ReportService', () => {
  const executiveUrl = `${environment.apiUrl}/reports/executive`;
  let service: ReportService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(ReportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('busca o PDF em GET /reports/executive, sem parâmetro algum', () => {
    let received: HttpResponse<Blob> | undefined;
    service.executive().subscribe((response) => (received = response));

    const request = httpMock.expectOne((candidate) => candidate.url === executiveUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    // The endpoint does not accept a date range: the numbers are those of the generation moment.
    expect(request.request.params.keys()).toEqual([]);

    request.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''relatorio-executivo.pdf" },
    });

    // The whole response reaches the screen: it is the only way to read the Content-Disposition.
    expect(received instanceof HttpResponse).toBeTrue();
    expect(received?.headers.get('Content-Disposition')).toContain('relatorio-executivo.pdf');
    expect(received?.body instanceof Blob).toBeTrue();
  });
});
