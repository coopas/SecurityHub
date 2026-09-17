import { HttpEventType, HttpResponse } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { Attachment } from '../models/attachment.model';
import { makeAttachment } from '../testing/vulnerability-test-utils';
import { AttachmentService } from './attachment.service';

describe('AttachmentService', () => {
  const attachmentsUrl = `${environment.apiUrl}/vulnerabilities/7/attachments`;
  let service: AttachmentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(AttachmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista os anexos como array puro, sem envelope de paginação', () => {
    let received: Attachment[] | undefined;
    service.list(7).subscribe((attachments) => (received = attachments));

    const request = httpMock.expectOne({ url: attachmentsUrl, method: 'GET' });
    const attachments = [makeAttachment(), makeAttachment({ id: 2, filename: 'log.txt' })];
    request.flush(attachments);

    expect(received).toEqual(attachments);
  });

  it('envia o arquivo como FormData, sem definir o Content-Type na mão', () => {
    const file = new File(['conteúdo'], 'evidencia.png', { type: 'image/png' });
    service.upload(7, file).subscribe();

    const request = httpMock.expectOne({ url: attachmentsUrl, method: 'POST' });
    const body = request.request.body as FormData;
    expect(body instanceof FormData).toBeTrue();
    // `append` com nome cria um File novo com o mesmo conteúdo, então a comparação é
    // pelo que viaja: a parte se chama `file` e leva o nome e o tamanho do original.
    const part = body.get('file') as File;
    expect(part.name).toBe('evidencia.png');
    expect(part.size).toBe(file.size);
    // Quem escreve o cabeçalho multipart é o navegador, porque só ele conhece o
    // boundary; defini-lo aqui produziria um Content-Type sem boundary algum.
    expect(request.request.headers.has('Content-Type')).toBeFalse();
    expect(request.request.reportProgress).toBeTrue();

    request.flush(makeAttachment());
  });

  it('relata o progresso do upload antes da resposta', () => {
    const events: number[] = [];
    let created: Attachment | undefined;
    service.upload(7, new File(['x'], 'log.txt', { type: 'text/plain' })).subscribe((event) => {
      if (event.type === HttpEventType.UploadProgress) {
        events.push(event.loaded);
      }
      if (event.type === HttpEventType.Response) {
        created = event.body ?? undefined;
      }
    });

    const request = httpMock.expectOne({ url: attachmentsUrl, method: 'POST' });
    request.event({ type: HttpEventType.UploadProgress, loaded: 512, total: 1024 });
    request.flush(makeAttachment({ id: 3 }));

    expect(events).toEqual([512]);
    expect(created?.id).toBe(3);
  });

  it('baixa o anexo como blob e entrega a resposta inteira, por causa do cabeçalho', () => {
    let received: HttpResponse<Blob> | undefined;
    service.download(7, 4).subscribe((response) => (received = response));

    const request = httpMock.expectOne({ url: `${attachmentsUrl}/4/download`, method: 'GET' });
    expect(request.request.responseType).toBe('blob');

    request.flush(new Blob(['bytes'], { type: 'image/png' }), {
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''evidencia.png" },
    });

    // A resposta inteira chega à tela: é o único jeito de ler o Content-Disposition.
    expect(received instanceof HttpResponse).toBeTrue();
    expect(received?.headers.get('Content-Disposition')).toContain('evidencia.png');
    expect(received?.body instanceof Blob).toBeTrue();
  });

  it('exclui o anexo pelo id, sob a vulnerabilidade', () => {
    service.delete(7, 4).subscribe();

    httpMock
      .expectOne({ url: `${attachmentsUrl}/4`, method: 'DELETE' })
      .flush(null, { status: 204, statusText: 'No Content' });
  });
});
