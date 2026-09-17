import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of, throwError } from 'rxjs';

import { ApiError, Role } from '../../../core/models';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { makeAsset } from '../testing/asset-test-utils';
import { AssetService } from '../services/asset.service';
import { AssetDetailComponent } from './asset-detail.component';

describe('AssetDetailComponent', () => {
  let fixture: ComponentFixture<AssetDetailComponent>;
  let component: AssetDetailComponent;
  let assetService: jasmine.SpyObj<AssetService>;
  let notifications: jasmine.SpyObj<NotificationService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;

  const setup = (role: Role = 'ADMIN', id = '7'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    assetService = jasmine.createSpyObj<AssetService>('AssetService', ['get', 'delete']);
    assetService.get.and.returnValue(
      of(
        makeAsset({
          id: 7,
          name: 'API de pagamentos',
          projectId: 3,
          projectName: 'Portal do cliente',
          criticality: 'CRITICAL',
          vulnerabilityCount: 0,
        }),
      ),
    );
    assetService.delete.and.returnValue(of(undefined));
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      declarations: [AssetDetailComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: AssetService, useValue: assetService },
        { provide: NotificationService, useValue: notifications },
        { provide: MatDialog, useValue: dialog },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id }) } } },
      ],
    });

    fixture = TestBed.createComponent(AssetDetailComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('exibe os dados do ativo', () => {
    setup();
    fixture.detectChanges();

    expect(assetService.get).toHaveBeenCalledWith(7);
    const element = fixture.nativeElement as HTMLElement;
    const text = element.textContent ?? '';
    expect(text).toContain('API de pagamentos');
    expect(text).toContain('Portal do cliente');
    expect(text).toContain('Produção');
    expect(text).toContain('api.pagamentos.local');
    expect(element.querySelector('[data-testid="asset-vulnerability-count"]')?.textContent).toContain('0');
  });

  it('mostra a criticidade com ícone e texto, nunca só por cor', () => {
    setup();
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="asset-criticality-value"]',
    );
    expect(badge?.querySelector('mat-icon')).not.toBeNull();
    expect(badge?.textContent).toContain('Crítica');
    expect(badge?.classList).toContain('assets-criticality--critical');
  });

  it('esconde editar e excluir para VIEWER', () => {
    setup('VIEWER');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="asset-edit"]')).toBeNull();
    expect(element.querySelector('[data-testid="asset-delete"]')).toBeNull();
  });

  it('mostra editar e excluir para ADMIN', () => {
    setup('ADMIN');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="asset-edit"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="asset-delete"]')).not.toBeNull();
  });

  it('exclui após confirmação e volta para a lista', () => {
    setup();
    fixture.detectChanges();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, boolean>);

    component.confirmDelete();

    expect(assetService.delete).toHaveBeenCalledWith(7);
    expect(notifications.success).toHaveBeenCalledWith('Ativo excluído.');
    expect(router.navigate).toHaveBeenCalledWith(['/assets']);
  });

  it('não exclui quando a confirmação é cancelada', () => {
    setup();
    fixture.detectChanges();
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown, boolean>);

    component.confirmDelete();

    expect(assetService.delete).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('mostra a mensagem do servidor quando a exclusão conflita com vulnerabilidades', () => {
    setup();
    fixture.detectChanges();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, boolean>);

    const conflict: ApiError = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 409,
      code: 'CONFLICT',
      message: 'O ativo possui vulnerabilidades vinculadas',
      path: '/api/v1/assets/7',
      traceId: 'trace',
    };
    assetService.delete.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: conflict })),
    );

    component.confirmDelete();
    fixture.detectChanges();

    expect(component.actionError).toBe('O ativo possui vulnerabilidades vinculadas');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'O ativo possui vulnerabilidades vinculadas',
    );
    expect(component.deleting).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('trata 404 como ativo não encontrado', () => {
    setup('ADMIN', '99');
    const notFound: ApiError = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 404,
      code: 'NOT_FOUND',
      message: 'Ativo 99 não encontrado',
      path: '/api/v1/assets/99',
      traceId: 'trace',
    };
    assetService.get.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404, error: notFound })),
    );

    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ativo não encontrado.');
    expect((fixture.nativeElement as HTMLElement).querySelector('.sh-state button')).toBeNull();
  });
});
