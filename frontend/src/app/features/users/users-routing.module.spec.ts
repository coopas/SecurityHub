import { HttpClientTestingModule } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';

import { Role } from '../../core/models';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  CURRENT_USER_STORAGE_KEY,
} from '../../core/services/auth.service';
import { makeJwt, makeUser } from '../../core/testing/auth-test-utils';
import { SharedModule } from '../../shared/shared.module';
import { InviteFormComponent } from './invite-form/invite-form.component';
import { InvitationService } from './services/invitation.service';
import { UserService } from './services/user.service';
import { makeAdminUser, makeInvitation } from './testing/user-test-utils';
import { UserDetailComponent } from './user-detail/user-detail.component';
import { UserListComponent } from './user-list/user-list.component';
import { USERS_ROUTES } from './users-routing.module';

/** Substitui as telas vizinhas (/403 e /login) sem arrastar os módulos delas. */
@Component({ selector: 'app-route-stub', template: '' })
class RouteStubComponent {}

describe('USERS_ROUTES', () => {
  let router: Router;

  const configure = (role: Role | null): void => {
    if (role) {
      localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
      localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));
    }

    const userService = jasmine.createSpyObj<UserService>('UserService', [
      'list',
      'get',
      'update',
      'changeRole',
      'changeActive',
    ]);
    userService.list.and.returnValue(of([makeAdminUser()]));
    userService.get.and.returnValue(of(makeAdminUser()));

    const invitationService = jasmine.createSpyObj<InvitationService>('InvitationService', [
      'list',
      'create',
      'revoke',
    ]);
    invitationService.list.and.returnValue(of([makeInvitation()]));

    TestBed.configureTestingModule({
      declarations: [
        UserListComponent,
        InviteFormComponent,
        UserDetailComponent,
        RouteStubComponent,
      ],
      imports: [
        SharedModule,
        HttpClientTestingModule,
        NoopAnimationsModule,
        RouterTestingModule.withRoutes([
          { path: 'users', children: USERS_ROUTES },
          { path: '403', component: RouteStubComponent },
          { path: 'login', component: RouteStubComponent },
        ]),
      ],
      providers: [
        { provide: UserService, useValue: userService },
        { provide: InvitationService, useValue: invitationService },
        { provide: MatDialog, useValue: jasmine.createSpyObj<MatDialog>('MatDialog', ['open']) },
      ],
    });

    router = TestBed.inject(Router);
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('abre a administração de usuários para ADMIN', async () => {
    configure('ADMIN');

    await router.navigateByUrl('/users');

    expect(router.url).toBe('/users');
  });

  it('desvia para 403 todo papel que o backend recusaria', async () => {
    for (const role of ['ANALYST', 'DEVELOPER', 'VIEWER'] as Role[]) {
      configure(role);

      await router.navigateByUrl('/users');

      expect(router.url).withContext(`papel ${role}`).toBe('/403');

      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  it('manda o visitante anônimo ao login guardando o destino', async () => {
    configure(null);

    await router.navigateByUrl('/users');

    expect(router.url).toBe('/login?returnUrl=%2Fusers');
  });

  it('protege também o convite e o detalhe', async () => {
    configure('VIEWER');

    await router.navigateByUrl('/users/convidar');
    expect(router.url).toBe('/403');

    await router.navigateByUrl('/users/1');
    expect(router.url).toBe('/403');
  });

  it('resolve convidar como rota própria, e não como identificador', async () => {
    configure('ADMIN');

    await router.navigateByUrl('/users/convidar');

    expect(router.url).toBe('/users/convidar');
    expect(router.routerState.snapshot.root.firstChild?.firstChild?.routeConfig?.path).toBe(
      'convidar',
    );
  });

  it('abre o detalhe por identificador', async () => {
    configure('ADMIN');

    await router.navigateByUrl('/users/7');

    expect(router.url).toBe('/users/7');
    expect(router.routerState.snapshot.root.firstChild?.firstChild?.routeConfig?.path).toBe(':id');
  });
});
