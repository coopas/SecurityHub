import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import { NotificationService } from '../../../core/services/notification.service';
import { SharedModule } from '../../../shared/shared.module';
import { makeCompanyUser } from '../testing/user-test-utils';
import { UserDetailComponent } from './user-detail.component';

describe('UserDetailComponent', () => {
  let fixture: ComponentFixture<UserDetailComponent>;
  let component: UserDetailComponent;
  let httpMock: HttpTestingController;
  let notifications: jasmine.SpyObj<NotificationService>;

  const setup = (id = '2'): void => {
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [UserDetailComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id }) } },
        },
        { provide: NotificationService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(UserDetailComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  };

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('carrega o usuário e preenche o nome', () => {
    setup();

    httpMock
      .expectOne(`${environment.apiUrl}/users/2`)
      .flush(makeCompanyUser(2, 'ANALYST', { name: 'Bruno Lima' }));
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(component.form.value.name).toBe('Bruno Lima');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('usuario2@empresa.com');
  });

  it('não chama o backend com identificador inválido', () => {
    setup('abc');

    expect(component.state).toBe('error');
    httpMock.expectNone(`${environment.apiUrl}/users/NaN`);
  });

  it('salva apenas o nome', () => {
    setup();
    httpMock.expectOne(`${environment.apiUrl}/users/2`).flush(makeCompanyUser(2));
    component.form.setValue({ name: ' Bruno Lima ' });

    component.submit();

    const request = httpMock.expectOne(`${environment.apiUrl}/users/2`);
    expect(request.request.method).toBe('PATCH');
    // The e-mail does not travel: it is the login and the recovery channel at the same time.
    expect(request.request.body).toEqual({ name: 'Bruno Lima' });
    request.flush(makeCompanyUser(2, 'ANALYST', { name: 'Bruno Lima' }));

    expect(notifications.success).toHaveBeenCalled();
    expect(component.user?.name).toBe('Bruno Lima');
  });
});
