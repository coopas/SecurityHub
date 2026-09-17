import { HttpEventType } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import { ApiError, ApiErrorCode } from '../../../core/models';
import { NotificationService } from '../../../core/services/notification.service';
import { SharedModule } from '../../../shared/shared.module';
import { makeProject, makeProjectPage } from '../../projects/testing/project-test-utils';
import { MAX_SCAN_FILE_BYTES } from '../models/scan-import.model';
import { makeScanImport } from '../testing/import-test-utils';
import { ImportUploadComponent } from './import-upload.component';

describe('ImportUploadComponent', () => {
  let fixture: ComponentFixture<ImportUploadComponent>;
  let component: ImportUploadComponent;
  let httpMock: HttpTestingController;
  let notifications: jasmine.SpyObj<NotificationService>;
  let router: Router;

  const importsUrl = `${environment.apiUrl}/scan-imports`;
  const projectsUrl = `${environment.apiUrl}/projects`;

  const apiError = (code: ApiErrorCode, message: string, status: number): ApiError => ({
    timestamp: '2026-09-17T12:00:00Z',
    status,
    code,
    message,
    path: '/api/v1/scan-imports',
    traceId: 'trace-1',
  });

  const setup = (projects = [makeProject({ id: 3 })]): void => {
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [ImportUploadComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [{ provide: NotificationService, useValue: notifications }],
    });

    fixture = TestBed.createComponent(ImportUploadComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);

    fixture.detectChanges();
    httpMock
      .expectOne((candidate) => candidate.url === projectsUrl)
      .flush(makeProjectPage(projects));
    fixture.detectChanges();
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const find = (testId: string): HTMLElement | null =>
    element().querySelector<HTMLElement>(`[data-testid="${testId}"]`);

  /** Simulates picking a file: the component reads `input.files` and clears `input.value`. */
  const pick = (file: File): HTMLInputElement => {
    const input = find('import-file-input') as HTMLInputElement;
    const transfer = new DataTransfer();
    transfer.items.add(file);
    input.files = transfer.files;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    return input;
  };

  const makeFile = (name = 'varredura.xml', type = 'text/xml'): File =>
    new File(['<nmaprun/>'], name, { type });

  const submitWith = (file: File = makeFile()): TestRequest => {
    component.form.patchValue({ projectId: 3, format: 'NMAP_XML' });
    pick(file);
    find('import-submit')?.click();
    fixture.detectChanges();
    return httpMock.expectOne({ url: importsUrl, method: 'POST' });
  };

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('carrega os projetos em uma página só para o seletor', () => {
    setup([makeProject({ id: 3 }), makeProject({ id: 4, name: 'Aplicativo' })]);

    expect(component.loadState).toBeNull();
    expect(component.projects.map((project) => project.id)).toEqual([3, 4]);
    expect(find('import-project')).not.toBeNull();
  });

  it('explica o que fazer quando a empresa ainda não tem projeto', () => {
    setup([]);

    expect(component.hasNoProjects).toBeTrue();
    expect(find('import-no-projects')).not.toBeNull();
    expect(find('import-submit')).toBeNull();
  });

  it('limpa o input depois de escolher, para que o mesmo arquivo dispare de novo', () => {
    setup();

    const input = pick(makeFile());

    expect(component.file?.name).toBe('varredura.xml');
    // Without this, re-picking the same file after an error would emit no `change`.
    expect(input.value).toBe('');
  });

  it('recusa antes de subir o arquivo maior que o teto, dizendo o tamanho', () => {
    setup();

    const big = new File([new Uint8Array(10)], 'gigante.xml', { type: 'text/xml' });
    Object.defineProperty(big, 'size', { value: MAX_SCAN_FILE_BYTES + 1 });
    pick(big);

    expect(component.file).toBeNull();
    expect(find('import-upload-error')?.textContent).toContain('o limite é');
    httpMock.expectNone({ url: importsUrl, method: 'POST' });
  });

  it('envia projeto, formato e arquivo como multipart e navega para a prévia', () => {
    setup();

    const request = submitWith();
    const body = request.request.body as FormData;
    expect(body instanceof FormData).toBeTrue();
    expect((body.get('file') as File).name).toBe('varredura.xml');
    expect(body.get('projectId')).toBe('3');
    expect(body.get('format')).toBe('NMAP_XML');
    expect(request.request.headers.has('Content-Type')).toBeFalse();

    request.flush(makeScanImport({ id: 12 }), { status: 201, statusText: 'Created' });
    fixture.detectChanges();

    expect(notifications.success).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/imports', 12]);
  });

  it('mostra a barra determinada enquanto o arquivo sobe', () => {
    setup();

    const request = submitWith();
    request.event({ type: HttpEventType.UploadProgress, loaded: 512, total: 1024 });
    fixture.detectChanges();

    expect(component.uploading).toBeTrue();
    expect(component.progress).toBe(50);
    expect(find('import-progress')).not.toBeNull();

    request.flush(makeScanImport(), { status: 201, statusText: 'Created' });
    fixture.detectChanges();
    expect(component.uploading).toBeFalse();
  });

  it('explica o PAYLOAD_TOO_LARGE devolvido pelo servidor', () => {
    setup();

    submitWith().flush(
      apiError('PAYLOAD_TOO_LARGE', 'O relatório excede 20 MB', 413),
      { status: 413, statusText: 'Payload Too Large' },
    );
    fixture.detectChanges();

    expect(find('import-upload-error')?.textContent).toContain('O relatório excede 20 MB');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('cai numa mensagem própria quando o 413 vem do proxy, sem envelope', () => {
    setup();

    submitWith().flush(null, { status: 413, statusText: 'Payload Too Large' });
    fixture.detectChanges();

    // With no envelope there is no `code`, so the default message is what is left.
    expect(find('import-upload-error')?.textContent).toContain(
      'Não foi possível enviar o relatório.',
    );
  });

  it('mostra literalmente a mensagem do BAD_REQUEST, que é escrita para ser acionável', () => {
    setup();

    submitWith().flush(
      apiError('BAD_REQUEST', 'O relatório tem 8000 achados e o limite é 5000. Divida a varredura.', 400),
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expect(component.errorMessage).toBe(
      'O relatório tem 8000 achados e o limite é 5000. Divida a varredura.',
    );
  });

  it('leva os fieldErrors para os controles do formulário', () => {
    setup();

    submitWith().flush(
      {
        ...apiError('VALIDATION_ERROR', 'Dados inválidos', 422),
        fieldErrors: [{ field: 'format', message: 'Formato incompatível com o arquivo' }],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    fixture.detectChanges();

    expect(component.form.controls['format'].getError('server')).toBe(
      'Formato incompatível com o arquivo',
    );
    expect(component.errorMessage).toBeNull();
  });

  it('não envia sem arquivo e diz o que falta', () => {
    setup();
    component.form.patchValue({ projectId: 3, format: 'NMAP_XML' });
    fixture.detectChanges();

    component.submit();
    fixture.detectChanges();

    expect(component.errorMessage).toBe('Escolha o arquivo do relatório antes de enviar.');
    httpMock.expectNone({ url: importsUrl, method: 'POST' });
  });

  it('o accept do seletor acompanha o formato escolhido', () => {
    setup();

    component.form.patchValue({ format: 'NUCLEI_JSONL' });
    fixture.detectChanges();

    expect(component.accept).toContain('.jsonl');
    expect((find('import-file-input') as HTMLInputElement).accept).toContain('.jsonl');
  });

  it('mostra o erro de carga dos projetos e permite tentar de novo', () => {
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);
    TestBed.configureTestingModule({
      declarations: [ImportUploadComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [{ provide: NotificationService, useValue: notifications }],
    });
    fixture = TestBed.createComponent(ImportUploadComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);

    fixture.detectChanges();
    httpMock
      .expectOne((candidate) => candidate.url === projectsUrl)
      .flush(null, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.loadState).toBe('error');

    element().querySelector<HTMLButtonElement>('.sh-state button')?.click();
    httpMock
      .expectOne((candidate) => candidate.url === projectsUrl)
      .flush(makeProjectPage([makeProject({ id: 3 })]));
    fixture.detectChanges();

    expect(component.loadState).toBeNull();
  });
});
