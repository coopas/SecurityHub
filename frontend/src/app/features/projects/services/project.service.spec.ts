import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { Project } from '../models/project.model';
import { makeProject, makeProjectPage } from '../testing/project-test-utils';
import { ProjectService } from './project.service';

describe('ProjectService', () => {
  const baseUrl = `${environment.apiUrl}/projects`;
  let service: ProjectService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(ProjectService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista enviando page, size e sort', () => {
    let received: PageResponse<Project> | undefined;
    service.list({ page: 2, size: 50, sort: 'name,asc' }).subscribe((page) => (received = page));

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('50');
    expect(request.request.params.get('sort')).toBe('name,asc');
    expect(request.request.params.has('search')).toBeFalse();
    expect(request.request.params.has('status')).toBeFalse();

    const page = makeProjectPage([makeProject()]);
    request.flush(page);
    expect(received).toEqual(page);
  });

  it('inclui search e status quando preenchidos', () => {
    service.list({ page: 0, size: 20, sort: 'createdAt,desc', search: 'portal', status: 'ARCHIVED' }).subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.params.get('search')).toBe('portal');
    expect(request.request.params.get('status')).toBe('ARCHIVED');
    request.flush(makeProjectPage([]));
  });

  it('busca um projeto por id', () => {
    service.get(7).subscribe();

    const request = httpMock.expectOne(`${baseUrl}/7`);
    expect(request.request.method).toBe('GET');
    request.flush(makeProject({ id: 7 }));
  });

  it('cria com POST no recurso', () => {
    const body = { name: 'Novo', description: 'Desc', status: 'ACTIVE' as const };
    service.create(body).subscribe();

    const request = httpMock.expectOne(baseUrl);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush(makeProject(), { status: 201, statusText: 'Created' });
  });

  it('atualiza com PUT no recurso identificado', () => {
    const body = { name: 'Editado' };
    service.update(9, body).subscribe();

    const request = httpMock.expectOne(`${baseUrl}/9`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(body);
    request.flush(makeProject({ id: 9, name: 'Editado' }));
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
