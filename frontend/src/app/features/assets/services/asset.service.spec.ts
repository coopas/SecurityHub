import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { Asset } from '../models/asset.model';
import { makeAsset, makeAssetPage } from '../testing/asset-test-utils';
import { AssetService } from './asset.service';

describe('AssetService', () => {
  const baseUrl = `${environment.apiUrl}/assets`;
  let service: AssetService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(AssetService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista enviando page, size e sort, sem filtros vazios', () => {
    let received: PageResponse<Asset> | undefined;
    service.list({ page: 2, size: 50, sort: 'name,asc' }).subscribe((page) => (received = page));

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('50');
    expect(request.request.params.get('sort')).toBe('name,asc');
    expect(request.request.params.has('search')).toBeFalse();
    expect(request.request.params.has('projectId')).toBeFalse();
    expect(request.request.params.has('type')).toBeFalse();
    expect(request.request.params.has('environment')).toBeFalse();
    expect(request.request.params.has('criticality')).toBeFalse();

    const page = makeAssetPage([makeAsset()]);
    request.flush(page);
    expect(received).toEqual(page);
  });

  it('inclui search, projectId, type, environment e criticality quando preenchidos', () => {
    service
      .list({
        page: 0,
        size: 20,
        sort: 'createdAt,desc',
        search: 'pagamentos',
        projectId: 3,
        type: 'API',
        environment: 'PRODUCTION',
        criticality: 'HIGH',
      })
      .subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.params.get('search')).toBe('pagamentos');
    expect(request.request.params.get('projectId')).toBe('3');
    expect(request.request.params.get('type')).toBe('API');
    expect(request.request.params.get('environment')).toBe('PRODUCTION');
    expect(request.request.params.get('criticality')).toBe('HIGH');
    request.flush(makeAssetPage([]));
  });

  it('busca um ativo por id', () => {
    service.get(7).subscribe();

    const request = httpMock.expectOne(`${baseUrl}/7`);
    expect(request.request.method).toBe('GET');
    request.flush(makeAsset({ id: 7 }));
  });

  it('cria com POST no recurso', () => {
    const body = {
      projectId: 3,
      name: 'Novo ativo',
      type: 'SERVER' as const,
      environment: 'STAGING' as const,
      criticality: 'LOW' as const,
    };
    service.create(body).subscribe();

    const request = httpMock.expectOne(baseUrl);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush(makeAsset(), { status: 201, statusText: 'Created' });
  });

  it('atualiza com PUT no recurso identificado', () => {
    const body = {
      projectId: 3,
      name: 'Editado',
      type: 'API' as const,
      environment: 'PRODUCTION' as const,
      criticality: 'HIGH' as const,
    };
    service.update(9, body).subscribe();

    const request = httpMock.expectOne(`${baseUrl}/9`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(body);
    request.flush(makeAsset({ id: 9, name: 'Editado' }));
  });

  it('exclui com DELETE e aceita 204 sem corpo', () => {
    let completed = false;
    service.delete(4).subscribe({ complete: () => (completed = true) });

    const request = httpMock.expectOne(`${baseUrl}/4`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBeTrue();
  });
});
