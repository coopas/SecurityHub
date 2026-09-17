import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../../core/services/auth.service';
import { makeJwt, makeUser } from '../../core/testing/auth-test-utils';
import { Role } from '../../core/models';
import { SharedModule } from '../../shared/shared.module';
import { MainLayoutComponent } from './main-layout.component';

describe('MainLayoutComponent', () => {
  let fixture: ComponentFixture<MainLayoutComponent>;
  let component: MainLayoutComponent;
  let router: Router;

  const setup = (role: Role): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    TestBed.configureTestingModule({
      declarations: [MainLayoutComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
    });

    fixture = TestBed.createComponent(MainLayoutComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('mostra Usuários e Auditoria para ADMIN', () => {
    setup('ADMIN');

    const routes = component.visibleNavItems(makeUser('ADMIN')).map((item) => item.route);
    expect(routes).toEqual(['/dashboard', '/projects', '/assets', '/vulnerabilities', '/users', '/audit']);
  });

  it('esconde as áreas administrativas dos demais papéis', () => {
    setup('ANALYST');

    const routes = component.visibleNavItems(makeUser('ANALYST')).map((item) => item.route);
    expect(routes).toEqual(['/dashboard', '/projects', '/assets', '/vulnerabilities']);
  });

  it('renderiza o skip link e a região principal', () => {
    setup('ADMIN');

    const element = fixture.nativeElement as HTMLElement;
    const skipLink = element.querySelector('.sh-skip-link');
    expect(skipLink?.getAttribute('href')).toBe('#main-content');
    expect(element.querySelector('main#main-content')).not.toBeNull();
    expect(element.textContent).toContain('SecurityHub');
  });

  it('logout limpa a sessão e volta ao login', () => {
    setup('ADMIN');

    component.logout();

    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
