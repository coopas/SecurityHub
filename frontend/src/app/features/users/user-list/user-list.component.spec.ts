import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSelect, MatSelectChange } from '@angular/material/select';
import { MatSlideToggle, MatSlideToggleChange } from '@angular/material/slide-toggle';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ApiError, User } from '../../../core/models';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  CURRENT_USER_STORAGE_KEY,
} from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { makeAdminUser, makeCompanyUser, makeInvitation } from '../testing/user-test-utils';
import { UserListComponent } from './user-list.component';

describe('UserListComponent', () => {
  let fixture: ComponentFixture<UserListComponent>;
  let component: UserListComponent;
  let httpMock: HttpTestingController;
  let dialog: jasmine.SpyObj<MatDialog>;
  let notifications: jasmine.SpyObj<NotificationService>;

  const usersUrl = `${environment.apiUrl}/users`;
  const invitationsUrl = `${environment.apiUrl}/invitations`;

  const conflict = (message: string): ApiError => ({
    timestamp: '2026-09-17T12:00:00Z',
    status: 409,
    code: 'CONFLICT',
    message,
    path: '/api/v1/users/2/active',
    traceId: 'trace-1',
  });

  /** The session is Ana's (id 1), who is also the first row of the table. */
  const setup = (
    users: User[] = [makeAdminUser(), makeCompanyUser(2, 'ANALYST')],
    invitations = [makeInvitation()],
  ): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser('ADMIN')));

    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    confirmWith(true);
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [UserListComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(UserListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpMock.expectOne(usersUrl).flush(users);
    httpMock.expectOne(invitationsUrl).flush(invitations);
    fixture.detectChanges();
  };

  const confirmWith = (answer: boolean): void => {
    dialog.open.and.returnValue({ afterClosed: () => of(answer) } as MatDialogRef<unknown, boolean>);
  };

  const toggleOf = (userId: number): MatSlideToggle =>
    fixture.debugElement.query(By.css(`[data-testid="user-active-${userId}"]`))
      .componentInstance as MatSlideToggle;

  const selectOf = (userId: number): MatSelect =>
    fixture.debugElement.query(By.css(`[data-testid="user-role-${userId}"]`))
      .componentInstance as MatSelect;

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('carrega usuários e convites pendentes em cards separados', () => {
    setup();

    expect(component.state).toBeNull();
    expect(component.dataSource.data.length).toBe(2);
    expect(component.pendingInvitations.length).toBe(1);
    // Pagination and sorting are client-side: the dataSource is the one that knows them.
    expect(component.dataSource.paginator).toBeTruthy();
    expect(component.dataSource.sort).toBeTruthy();
  });

  it('esconde da lista de convites os que já foram aceitos ou revogados', () => {
    setup(
      [makeAdminUser()],
      [makeInvitation(), makeInvitation({ id: 6, status: 'ACCEPTED' }), makeInvitation({ id: 7, status: 'REVOKED' })],
    );

    expect(component.pendingInvitations.map((invitation) => invitation.id)).toEqual([5]);
  });

  it('filtra no cliente por nome, e-mail ou papel', () => {
    setup([
      makeAdminUser(),
      makeCompanyUser(2, 'ANALYST', { name: 'Bruno Lima', email: 'bruno@empresa.com' }),
    ]);

    component.searchControl.setValue('bruno');
    expect(component.dataSource.filteredData.map((user) => user.id)).toEqual([2]);

    component.searchControl.setValue('administrador');
    expect(component.dataSource.filteredData.map((user) => user.id)).toEqual([1]);

    component.searchControl.setValue('');
    expect(component.dataSource.filteredData.length).toBe(2);
  });

  it('desativa pelo endpoint de situação e confirma antes', () => {
    setup();
    const toggle = toggleOf(2);
    toggle.checked = false;

    component.onActiveChange(component.dataSource.data[1], new MatSlideToggleChange(toggle, false));

    expect(dialog.open).toHaveBeenCalled();
    const request = httpMock.expectOne(`${usersUrl}/2/active`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ active: false });
    request.flush(makeCompanyUser(2, 'ANALYST', { active: false }));

    expect(component.dataSource.data[1].active).toBeFalse();
    expect(notifications.success).toHaveBeenCalled();
  });

  it('desfaz o toggle quando a confirmação é cancelada', () => {
    setup();
    confirmWith(false);
    const toggle = toggleOf(2);
    toggle.checked = false;

    component.onActiveChange(component.dataSource.data[1], new MatSlideToggleChange(toggle, false));

    expect(toggle.checked).toBeTrue();
    httpMock.expectNone(`${usersUrl}/2/active`);
  });

  it('reverte o toggle e mostra a mensagem do backend no 409', () => {
    setup([makeAdminUser(), makeCompanyUser(2, 'ADMIN')]);
    const toggle = toggleOf(2);
    toggle.checked = false;

    component.onActiveChange(component.dataSource.data[1], new MatSlideToggleChange(toggle, false));

    httpMock
      .expectOne(`${usersUrl}/2/active`)
      .flush(conflict('A empresa precisa de ao menos um administrador ativo'), {
        status: 409,
        statusText: 'Conflict',
      });
    fixture.detectChanges();

    // The control is optimistic: without undoing, the screen would show a state the server refused.
    expect(toggle.checked).toBeTrue();
    expect(component.dataSource.data[1].active).toBeTrue();
    expect(component.actionError).toBe('A empresa precisa de ao menos um administrador ativo');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="user-action-error"]')
        ?.textContent,
    ).toContain('A empresa precisa de ao menos um administrador ativo');
  });

  it('desabilita papel e situação na própria linha', () => {
    setup();

    expect(component.isSelf(component.dataSource.data[0])).toBeTrue();
    expect(toggleOf(1).disabled).toBeTrue();
    expect(selectOf(1).disabled).toBeTrue();

    expect(component.isSelf(component.dataSource.data[1])).toBeFalse();
    expect(toggleOf(2).disabled).toBeFalse();
    expect(selectOf(2).disabled).toBeFalse();
  });

  it('altera o papel pelo endpoint próprio e reverte o seletor no 409', () => {
    setup([makeAdminUser(), makeCompanyUser(2, 'ADMIN')]);
    const select = selectOf(2);
    select.value = 'VIEWER';

    component.onRoleChange(component.dataSource.data[1], new MatSelectChange(select, 'VIEWER'));

    const request = httpMock.expectOne(`${usersUrl}/2/role`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ role: 'VIEWER' });
    request.flush(conflict('A empresa precisa de ao menos um administrador ativo'), {
      status: 409,
      statusText: 'Conflict',
    });

    expect(select.value).toBe('ADMIN');
    expect(component.dataSource.data[1].role).toBe('ADMIN');
    expect(component.actionError).toBe('A empresa precisa de ao menos um administrador ativo');
  });

  it('ignora a troca de papel para o papel que já está em vigor', () => {
    setup();
    const select = selectOf(2);

    component.onRoleChange(component.dataSource.data[1], new MatSelectChange(select, 'ANALYST'));

    expect(dialog.open).not.toHaveBeenCalled();
    httpMock.expectNone(`${usersUrl}/2/role`);
  });

  it('revoga um convite pendente e recarrega a lista', () => {
    setup();

    component.revokeInvitation(component.pendingInvitations[0]);

    const request = httpMock.expectOne(`${invitationsUrl}/5`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });

    httpMock.expectOne(invitationsUrl).flush([]);
    expect(component.pendingInvitations.length).toBe(0);
    expect(notifications.success).toHaveBeenCalled();
  });

  it('mostra estado de erro quando a listagem falha', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser('ADMIN')));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error']);

    TestBed.configureTestingModule({
      declarations: [UserListComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(UserListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpMock.expectOne(usersUrl).flush(null, { status: 500, statusText: 'Internal Server Error' });
    httpMock.expectOne(invitationsUrl).flush([]);
    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(component.invitationsState).toBe('empty');
  });
});
