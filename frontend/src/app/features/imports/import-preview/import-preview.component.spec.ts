import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ApiError, ApiErrorCode, Role } from '../../../core/models';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  CURRENT_USER_STORAGE_KEY,
} from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
import { ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { SharedModule } from '../../../shared/shared.module';
import { makeAsset, makeAssetPage } from '../../assets/testing/asset-test-utils';
import { ScanFinding, ScanImport } from '../models/scan-import.model';
import { makeScanFinding, makeScanImport } from '../testing/import-test-utils';
import { ImportPreviewComponent } from './import-preview.component';

describe('ImportPreviewComponent', () => {
  let fixture: ComponentFixture<ImportPreviewComponent>;
  let component: ImportPreviewComponent;
  let httpMock: HttpTestingController;
  let dialog: jasmine.SpyObj<MatDialog>;
  let notifications: jasmine.SpyObj<NotificationService>;
  let router: Router;

  const importUrl = `${environment.apiUrl}/scan-imports/4`;
  const assetsUrl = `${environment.apiUrl}/assets`;

  const apiError = (code: ApiErrorCode, message: string, status: number): ApiError => ({
    timestamp: '2026-09-17T12:00:00Z',
    status,
    code,
    message,
    path: '/api/v1/scan-imports/4',
    traceId: 'trace-1',
  });

  const confirmWith = (answer: boolean): void => {
    dialog.open.and.returnValue({ afterClosed: () => of(answer) } as MatDialogRef<unknown, boolean>);
  };

  const create = (role: Role = 'ANALYST', id = '4'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    confirmWith(true);
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [ImportPreviewComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: notifications },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id }) } } },
      ],
    });

    fixture = TestBed.createComponent(ImportPreviewComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  /** Carrega a importação e, quando ela ainda está pendente, os ativos do projeto. */
  const setup = (scanImport: ScanImport = makeScanImport(), role: Role = 'ANALYST'): void => {
    create(role);
    fixture.detectChanges();
    httpMock.expectOne(importUrl).flush(scanImport);
    fixture.detectChanges();

    if (scanImport.status === 'PENDING' && component.canImport) {
      httpMock
        .expectOne((candidate) => candidate.url === assetsUrl)
        .flush(makeAssetPage([makeAsset({ id: 7, name: 'Servidor de borda' })]));
      fixture.detectChanges();
    }
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (): string => element().textContent ?? '';
  const rows = (): NodeListOf<Element> => element().querySelectorAll('tr[mat-row]');
  const find = (testId: string): HTMLElement | null =>
    element().querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const dialogData = (): ConfirmDialogData =>
    (dialog.open.calls.mostRecent().args[1] as { data: ConfirmDialogData }).data;

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('renderiza cada achado com a sua situação', () => {
    setup(
      makeScanImport({
        findings: [
          makeScanFinding({ id: 1, status: 'MATCHED' }),
          makeScanFinding({ id: 2, status: 'UNMATCHED', assetId: null, assetName: null }),
          makeScanFinding({ id: 3, status: 'DUPLICATE' }),
          makeScanFinding({ id: 4, status: 'IMPORTED' }),
          makeScanFinding({ id: 5, status: 'SKIPPED' }),
        ],
      }),
    );

    expect(rows().length).toBe(5);
    for (const label of [
      'Ativo identificado',
      'Sem ativo',
      'Já registrado',
      'Importado',
      'Ignorado',
    ]) {
      expect(text()).withContext(label).toContain(label);
    }
    // Situação e severidade nunca só por cor: cada marcador leva ícone e texto.
    expect(find('import-finding-status-2')?.querySelector('mat-icon')).not.toBeNull();
    expect(element().querySelector('.imports-chip mat-icon')).not.toBeNull();
  });

  it('mostra a faixa de contadores vinda do servidor', () => {
    setup();

    const values = Array.from(
      find('import-counters')?.querySelectorAll('dd') ?? [],
    ).map((cell) => cell.textContent?.trim());
    expect(values).toEqual(['3', '1', '1', '1', '0', '0']);
  });

  it('filtra e ordena no cliente, sem tocar na URL', () => {
    setup();

    component.searchControl.setValue('8080');
    expect(component.dataSource.filteredData.map((finding) => finding.id)).toEqual([2]);

    component.searchControl.setValue('');
    expect(component.dataSource.filteredData.length).toBe(3);
    expect(component.dataSource.sort).toBeTruthy();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('oferece o seletor de ativo apenas nos achados sem ativo', () => {
    setup();

    expect(find('import-finding-asset-2')).not.toBeNull();
    expect(find('import-finding-asset-1')).toBeNull();
    expect(find('import-finding-asset-3')).toBeNull();
  });

  it('vincula o achado ao ativo e atualiza a linha com o que o servidor devolveu', () => {
    setup();

    component.onAssetSelected(component.dataSource.data[1], 7);

    const request = httpMock.expectOne(`${importUrl}/findings/2`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ assetId: 7 });
    request.flush(
      makeScanFinding({
        id: 2,
        title: 'Porta 8080 exposta',
        status: 'MATCHED',
        assetId: 7,
        assetName: 'Servidor de borda',
      }),
    );
    fixture.detectChanges();

    const updated = component.dataSource.data[1];
    expect(updated.status).toBe('MATCHED');
    expect(updated.assetName).toBe('Servidor de borda');
    expect(find('import-finding-asset-2')).toBeNull();
    expect(text()).toContain('Servidor de borda');
    expect(notifications.success).toHaveBeenCalled();
    // A linha é substituída sem recarregar a importação inteira.
    httpMock.expectNone(importUrl);
  });

  it('respeita a reclassificação do servidor, que pode devolver já registrado', () => {
    setup();

    component.onAssetSelected(component.dataSource.data[1], 7);
    httpMock
      .expectOne(`${importUrl}/findings/2`)
      .flush(makeScanFinding({ id: 2, status: 'DUPLICATE', assetId: 7, assetName: 'Servidor de borda' }));
    fixture.detectChanges();

    expect(component.dataSource.data[1].status).toBe('DUPLICATE');
  });

  it('mostra o erro do servidor quando o vínculo falha, sem mudar a linha', () => {
    setup();

    component.onAssetSelected(component.dataSource.data[1], 7);
    httpMock
      .expectOne(`${importUrl}/findings/2`)
      .flush(apiError('NOT_FOUND', 'Ativo não encontrado neste projeto', 404), {
        status: 404,
        statusText: 'Not Found',
      });
    fixture.detectChanges();

    expect(component.dataSource.data[1].status).toBe('UNMATCHED');
    expect(find('import-action-error')?.textContent).toContain(
      'Ativo não encontrado neste projeto',
    );
  });

  it('confirma pelo diálogo compartilhado, dizendo quantas serão criadas e quantas ignoradas', () => {
    setup();

    find('import-confirm')?.click();
    fixture.detectChanges();

    expect(dialog.open).toHaveBeenCalled();
    const data = dialogData();
    expect(data.title).toBe('Confirmar importação');
    expect(data.message).toContain('1 vulnerabilidade será criada');
    expect(data.message).toContain('2 achados');

    const request = httpMock.expectOne(`${importUrl}/confirm`);
    expect(request.request.method).toBe('POST');
    request.flush(makeScanImport({ status: 'CONFIRMED', importedCount: 1, skippedCount: 2 }));
    fixture.detectChanges();

    expect(component.scanImport?.status).toBe('CONFIRMED');
    expect(find('import-actions')).toBeNull();
    expect(notifications.success).toHaveBeenCalled();
  });

  it('não chama o servidor quando a confirmação é cancelada', () => {
    setup();
    confirmWith(false);

    find('import-confirm')?.click();
    fixture.detectChanges();

    httpMock.expectNone(`${importUrl}/confirm`);
    expect(component.scanImport?.status).toBe('PENDING');
  });

  it('descarta pelo mesmo diálogo, marcado como destrutivo, e volta ao histórico', () => {
    setup();

    find('import-discard')?.click();
    fixture.detectChanges();

    const data = dialogData();
    expect(data.title).toBe('Descartar importação');
    expect(data.destructive).toBeTrue();
    expect(data.message).toContain('3 achados');

    httpMock.expectOne(importUrl).flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledWith(['/imports']);
  });

  it('não chama o servidor quando o descarte é cancelado', () => {
    setup();
    confirmWith(false);

    find('import-discard')?.click();
    fixture.detectChanges();

    httpMock.expectNone(importUrl);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('uma importação confirmada é histórico: nada de botões nem de seletores', () => {
    setup(
      makeScanImport({
        status: 'CONFIRMED',
        importedCount: 1,
        skippedCount: 2,
        findings: [makeScanFinding({ id: 2, status: 'SKIPPED', assetId: null, assetName: null })],
      }),
    );

    expect(component.isPending).toBeFalse();
    expect(find('import-actions')).toBeNull();
    expect(find('import-confirm')).toBeNull();
    expect(find('import-discard')).toBeNull();
    expect(find('import-finding-asset-2')).toBeNull();
    expect(find('import-readonly-hint')).not.toBeNull();
    // Os ativos nem chegam a ser buscados: não há seletor para alimentar.
    httpMock.expectNone((candidate) => candidate.url === assetsUrl);
  });

  it('uma importação descartada também aparece somente como histórico', () => {
    setup(makeScanImport({ status: 'DISCARDED' }));

    expect(find('import-actions')).toBeNull();
    expect(text()).toContain('Descartada');
  });

  it('um achado sem CVSS não imprime a nota: a API omite o campo em vez de mandar null', () => {
    // Reproduz o corpo real: `default-property-inclusion: non_null` apaga a chave, então
    // o que chega é `undefined`, e não o `null` que as outras fixtures usam. Um Nmap sem
    // CVSS cai exatamente aqui.
    const semCvss = makeScanFinding({ id: 7, cve: null });
    delete (semCvss as Partial<ScanFinding>).cvssScore;

    setup(makeScanImport({ findings: [semCvss], totalFindings: 1 }));

    expect(text()).not.toContain('CVSS');
  });

  it('esconde as ações de quem não pode criar vulnerabilidades', () => {
    setup(makeScanImport(), 'VIEWER');

    expect(component.canImport).toBeFalse();
    expect(find('import-actions')).toBeNull();
    expect(find('import-no-permission')).not.toBeNull();
    // O achado 2 chega UNMATCHED: sem esta asserção, `canAct` pode ser trocado por
    // `true` sem quebrar teste nenhum, e um leitor ganha um seletor que só sabe
    // responder 403. Esconder o botão de confirmar não basta se a linha continua editável.
    expect(find('import-finding-asset-2')).toBeNull();
  });

  it('um CONFLICT no confirmar recarrega a importação e explica o que houve', () => {
    setup();

    find('import-confirm')?.click();
    fixture.detectChanges();

    httpMock
      .expectOne(`${importUrl}/confirm`)
      .flush(apiError('CONFLICT', 'Esta importação já foi confirmada', 409), {
        status: 409,
        statusText: 'Conflict',
      });
    fixture.detectChanges();

    expect(component.actionError).toBe('Esta importação já foi confirmada');

    // Recarregar é o que traz a tela de volta à verdade; a partir daí ela esconde tudo.
    httpMock.expectOne(importUrl).flush(makeScanImport({ status: 'CONFIRMED' }));
    fixture.detectChanges();

    expect(find('import-action-error')?.textContent).toContain(
      'Esta importação já foi confirmada',
    );
    expect(find('import-actions')).toBeNull();
  });

  it('trata o NOT_FOUND da carga sem oferecer nova tentativa', () => {
    create();
    fixture.detectChanges();
    httpMock
      .expectOne(importUrl)
      .flush(apiError('NOT_FOUND', 'Importação não encontrada', 404), {
        status: 404,
        statusText: 'Not Found',
      });
    fixture.detectChanges();

    expect(component.notFound).toBeTrue();
    expect(text()).toContain('Importação não encontrada.');
    expect(element().querySelector('.sh-state button')).toBeNull();
  });

  it('recusa um id que não é identificador sem chamar o servidor', () => {
    create('ANALYST', 'abc');
    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(component.notFound).toBeTrue();
    httpMock.expectNone(importUrl);
  });

  it('uma falha ao listar ativos não esconde a prévia', () => {
    create();
    fixture.detectChanges();
    httpMock.expectOne(importUrl).flush(makeScanImport());
    fixture.detectChanges();
    httpMock
      .expectOne((candidate) => candidate.url === assetsUrl)
      .flush(null, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(component.assetsState).toBe('error');
    expect(rows().length).toBe(3);
    expect(find('import-assets-error')).not.toBeNull();
  });

  it('avisa quando o projeto não tem ativo nenhum para vincular', () => {
    create();
    fixture.detectChanges();
    httpMock.expectOne(importUrl).flush(makeScanImport());
    fixture.detectChanges();
    httpMock.expectOne((candidate) => candidate.url === assetsUrl).flush(makeAssetPage([]));
    fixture.detectChanges();

    expect(find('import-assets-empty')).not.toBeNull();
  });

  it('mostra o estado vazio quando o relatório não trouxe achados', () => {
    setup(makeScanImport({ findings: [], totalFindings: 0 }));

    expect(component.findingsState).toBe('empty');
    expect(text()).toContain('O relatório não trouxe nenhum achado.');
  });

  it('a tabela e a legenda são anunciadas para leitores de tela', () => {
    setup();

    const table = element().querySelector('table');
    expect(table?.getAttribute('aria-label')).toBe('Achados da importação');
    expect(table?.querySelector('caption')?.classList).toContain('sh-visually-hidden');
    expect(element().querySelectorAll('th[scope="col"]').length).toBe(
      component.displayedColumns.length,
    );
  });
});
