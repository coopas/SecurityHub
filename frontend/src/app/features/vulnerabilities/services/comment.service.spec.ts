import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { makeComment, makeCommentPage } from '../testing/vulnerability-test-utils';
import { CommentService } from './comment.service';

describe('CommentService', () => {
  const commentsUrl = `${environment.apiUrl}/vulnerabilities/7/comments`;
  let service: CommentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(CommentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista os comentários da vulnerabilidade com paginação', () => {
    service.list(7, 1, 20).subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url === commentsUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.get('size')).toBe('20');
    request.flush(makeCommentPage([makeComment()]));
  });

  it('cria e edita comentários nos endpoints aninhados', () => {
    service.create(7, { content: 'Reproduzi em homologação.' }).subscribe();
    const created = httpMock.expectOne({ url: commentsUrl, method: 'POST' });
    expect(created.request.body).toEqual({ content: 'Reproduzi em homologação.' });
    created.flush(makeComment());

    service.update(7, 3, { content: 'Corrigido na branch de hotfix.' }).subscribe();
    const updated = httpMock.expectOne({ url: `${commentsUrl}/3`, method: 'PUT' });
    expect(updated.request.body).toEqual({ content: 'Corrigido na branch de hotfix.' });
    updated.flush(makeComment({ id: 3 }));
  });
});
