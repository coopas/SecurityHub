import { HttpClientTestingModule } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';

import { Role } from '../../core/models';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../../core/services/auth.service';
import { makeJwt, makeUser } from '../../core/testing/auth-test-utils';
import { SharedModule } from '../../shared/shared.module';
import { AuditDiffComponent } from './audit-diff/audit-diff.component';
import { AuditListComponent } from './audit-list/audit-list.component';
import { AUDIT_ROUTES } from './audit-routing.module';
import { AuditActorService } from './services/audit-actor.service';
import { AuditService } from './services/audit.service';
import { makeAuditPage } from './testing/audit-test-utils';

/** Substitui as telas vizinhas (/403 e /login) sem arrastar os módulos delas. */
@Component({ selector: 'app-route-stub', template: '' })
class RouteStubComponent {}

describe('AUDIT_ROUTES', () => {
  let router: Router;

  const configure = (role: Role | null): void => {
    if (role) {
      localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
      localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));
    }

    const auditService = jasmine.createSpyObj<AuditService>('AuditService', ['list']);
    auditService.list.and.returnValue(of(makeAuditPage([])));
    const actorService = jasmine.createSpyObj<AuditActorService>('AuditActorService', ['list']);
    actorService.list.and.returnValue(of([]));

    TestBed.configureTestingModule({
      declarations: [AuditListComponent, AuditDiffComponent, RouteStubComponent],
      imports: [
        SharedModule,
        HttpClientTestingModule,
        NoopAnimationsModule,
        RouterTestingModule.withRoutes([
          { path: 'audit', children: AUDIT_ROUTES },
          { path: '403', component: RouteStubComponent },
          { path: 'login', component: RouteStubComponent },
        ]),
      ],
      providers: [
        { provide: AuditService, useValue: auditService },
        { provide: AuditActorService, useValue: actorService },
      ],
    });

    router = TestBed.inject(Router);
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('abre a trilha para ADMIN', async () => {
    configure('ADMIN');

    await router.navigateByUrl('/audit');

    expect(router.url).toBe('/audit');
  });

  it('desvia para 403 todo papel que o backend recusaria', async () => {
    for (const role of ['ANALYST', 'DEVELOPER', 'VIEWER'] as Role[]) {
      configure(role);

      await router.navigateByUrl('/audit');

      expect(router.url)
        .withContext(`papel ${role}`)
        .toBe('/403');

      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  it('manda o visitante anônimo ao login guardando o destino', async () => {
    configure(null);

    await router.navigateByUrl('/audit');

    expect(router.url).toBe('/login?returnUrl=%2Faudit');
  });

  it('preserva os filtros da URL ao entrar como ADMIN', async () => {
    configure('ADMIN');

    await router.navigateByUrl('/audit?action=DELETE&from=2026-09-01');

    expect(router.url).toBe('/audit?action=DELETE&from=2026-09-01');
  });
});
