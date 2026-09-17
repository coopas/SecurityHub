import { HttpClientTestingModule } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { APP_ROUTES } from './app-routing.module';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from './core/services/auth.service';
import { makeJwt, makeUser } from './core/testing/auth-test-utils';
import { ErrorsModule } from './features/errors/errors.module';
import { LayoutModule } from './layout/layout.module';

describe('APP_ROUTES', () => {
  let router: Router;

  const configure = (authenticated: boolean): void => {
    if (authenticated) {
      localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
      localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    }

    TestBed.configureTestingModule({
      imports: [
        HttpClientTestingModule,
        LayoutModule,
        ErrorsModule,
        RouterTestingModule.withRoutes(APP_ROUTES),
      ],
    });

    router = TestBed.inject(Router);
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('envia visitante anônimo de /dashboard para o login com returnUrl', async () => {
    configure(false);

    await router.navigateByUrl('/dashboard');

    expect(router.url).toBe('/login?returnUrl=%2Fdashboard');
  });

  it('deixa o visitante anônimo abrir o cadastro', async () => {
    configure(false);

    await router.navigateByUrl('/register');

    expect(router.url).toBe('/register');
  });

  it('devolve o usuário autenticado do login para o dashboard', async () => {
    configure(true);

    await router.navigateByUrl('/login');

    expect(router.url).toBe('/dashboard');
  });

  it('resolve a raiz para o dashboard quando autenticado', async () => {
    configure(true);

    await router.navigateByUrl('/');

    expect(router.url).toBe('/dashboard');
  });

  it('cai no 404 para rotas desconhecidas', async () => {
    configure(true);

    await router.navigateByUrl('/rota-que-nao-existe');

    expect(router.url).toBe('/rota-que-nao-existe');
    expect(router.routerState.snapshot.root.firstChild?.routeConfig?.path).toBe('**');
  });

  it('mantém 403 e 404 acessíveis diretamente', async () => {
    configure(true);

    await router.navigateByUrl('/403');
    expect(router.url).toBe('/403');

    await router.navigateByUrl('/404');
    expect(router.url).toBe('/404');
  });
});
