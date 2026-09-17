import { HttpClientTestingModule } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../services/auth.service';
import { makeJwt, makeUser } from '../testing/auth-test-utils';
import { Role } from '../models';
import { authGuard } from './auth.guard';
import { guestGuard } from './guest.guard';
import { roleGuard } from './role.guard';

function snapshotWithRoles(roles?: Role[]): ActivatedRouteSnapshot {
  return { data: roles ? { roles } : {} } as unknown as ActivatedRouteSnapshot;
}

function stateFor(url: string): RouterStateSnapshot {
  return { url } as RouterStateSnapshot;
}

describe('guards de rota', () => {
  const signIn = (role: Role = 'ADMIN'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule, RouterTestingModule] });
  });

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  describe('authGuard', () => {
    it('libera o acesso com sessão válida', () => {
      signIn();
      const result = TestBed.runInInjectionContext(() =>
        authGuard(snapshotWithRoles(), stateFor('/dashboard')),
      );
      expect(result).toBeTrue();
    });

    it('redireciona ao login preservando returnUrl', () => {
      const result = TestBed.runInInjectionContext(() =>
        authGuard(snapshotWithRoles(), stateFor('/vulnerabilities/7')),
      );
      expect(result instanceof UrlTree).toBeTrue();
      expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe(
        '/login?returnUrl=%2Fvulnerabilities%2F7',
      );
    });
  });

  describe('roleGuard', () => {
    it('libera quando o papel está na lista', () => {
      signIn('ADMIN');
      const result = TestBed.runInInjectionContext(() =>
        roleGuard(snapshotWithRoles(['ADMIN']), stateFor('/users')),
      );
      expect(result).toBeTrue();
    });

    it('envia para 403 quando o papel não é permitido', () => {
      signIn('VIEWER');
      const result = TestBed.runInInjectionContext(() =>
        roleGuard(snapshotWithRoles(['ADMIN']), stateFor('/users')),
      );
      expect(result instanceof UrlTree).toBeTrue();
      expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/403');
    });

    it('sem sessão envia ao login, não ao 403', () => {
      const result = TestBed.runInInjectionContext(() =>
        roleGuard(snapshotWithRoles(['ADMIN']), stateFor('/audit')),
      );
      expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe(
        '/login?returnUrl=%2Faudit',
      );
    });
  });

  describe('guestGuard', () => {
    it('libera visitante anônimo', () => {
      const result = TestBed.runInInjectionContext(() =>
        guestGuard(snapshotWithRoles(), stateFor('/login')),
      );
      expect(result).toBeTrue();
    });

    it('manda usuário autenticado para o dashboard', () => {
      signIn();
      const result = TestBed.runInInjectionContext(() =>
        guestGuard(snapshotWithRoles(), stateFor('/login')),
      );
      expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/dashboard');
    });
  });
});
