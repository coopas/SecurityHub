import { HttpEventType } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { ScanImport, ScanImportSummary } from '../models/scan-import.model';
import {
  makeScanFinding,
  makeScanImport,
  makeScanImportPage,
  makeScanImportSummary,
} from '../testing/import-test-utils';
import { ImportService } from './import.service';

describe('ImportService', () => {
  const baseUrl = `${environment.apiUrl}/scan-imports`;
  let service: ImportService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(ImportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('lista o histórico enviando page, size e sort', () => {
    let received: PageResponse<ScanImportSummary> | undefined;
    service
      .list({ page: 2, size: 50, sort: 'createdAt,desc' })
      .subscribe((page) => (received = page));

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('50');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');

    const page = makeScanImportPage([makeScanImportSummary()]);
    request.flush(page);
    expect(received).toEqual(page);
  });

  it('busca a importação com os achados por id', () => {
    let received: ScanImport | undefined;
    service.get(4).subscribe((scanImport) => (received = scanImport));

    const request = httpMock.expectOne(`${baseUrl}/4`);
    expect(request.request.method).toBe('GET');
    request.flush(makeScanImport());

    expect(received?.findings.length).toBe(3);
  });

  it('envia o relatório como FormData, sem definir o Content-Type na mão', () => {
    const file = new File(['<nmaprun/>'], 'varredura.xml', { type: 'text/xml' });
    service.upload(3, 'NMAP_XML', file).subscribe();

    const request = httpMock.expectOne({ url: baseUrl, method: 'POST' });
    const body = request.request.body as FormData;
    expect(body instanceof FormData).toBeTrue();
    // `append` com nome cria um File novo com o mesmo conteúdo, então a comparação é
    // pelo que viaja: a parte se chama `file` e leva o nome e o tamanho do original.
    const part = body.get('file') as File;
    expect(part.name).toBe('varredura.xml');
    expect(part.size).toBe(file.size);
    expect(body.get('projectId')).toBe('3');
    expect(body.get('format')).toBe('NMAP_XML');
    // Quem escreve o cabeçalho multipart é o navegador, porque só ele conhece o
    // boundary; defini-lo aqui produziria um Content-Type sem boundary algum.
    expect(request.request.headers.has('Content-Type')).toBeFalse();
    expect(request.request.reportProgress).toBeTrue();

    request.flush(makeScanImport(), { status: 201, statusText: 'Created' });
  });

  it('relata o progresso do upload antes da resposta', () => {
    const loaded: number[] = [];
    let created: ScanImport | undefined;
    service
      .upload(3, 'ZAP_JSON', new File(['{}'], 'zap.json', { type: 'application/json' }))
      .subscribe((event) => {
        if (event.type === HttpEventType.UploadProgress) {
          loaded.push(event.loaded);
        }
        if (event.type === HttpEventType.Response) {
          created = event.body ?? undefined;
        }
      });

    const request = httpMock.expectOne({ url: baseUrl, method: 'POST' });
    request.event({ type: HttpEventType.UploadProgress, loaded: 256, total: 1024 });
    request.flush(makeScanImport({ id: 9 }), { status: 201, statusText: 'Created' });

    expect(loaded).toEqual([256]);
    expect(created?.id).toBe(9);
  });

  it('vincula o achado ao ativo com PATCH e devolve o achado reclassificado', () => {
    service.mapFinding(4, 2, 7).subscribe();

    const request = httpMock.expectOne(`${baseUrl}/4/findings/2`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ assetId: 7 });
    request.flush(makeScanFinding({ id: 2, status: 'MATCHED', assetId: 7 }));
  });

  it('confirma a importação com POST no subrecurso', () => {
    service.confirm(4).subscribe();

    const request = httpMock.expectOne(`${baseUrl}/4/confirm`);
    expect(request.request.method).toBe('POST');
    request.flush(makeScanImport({ status: 'CONFIRMED', importedCount: 1 }));
  });

  it('descarta com DELETE e aceita 204 sem corpo', () => {
    let completed = false;
    service.discard(4).subscribe({ complete: () => (completed = true) });

    const request = httpMock.expectOne(`${baseUrl}/4`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBeTrue();
  });
});
