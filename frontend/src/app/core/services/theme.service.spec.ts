import { TestBed } from '@angular/core/testing';

import { THEME_STORAGE_KEY, ThemeService } from './theme.service';

describe('ThemeService', () => {
  let root: HTMLElement;

  const create = (): ThemeService => {
    TestBed.configureTestingModule({});
    return TestBed.inject(ThemeService);
  };

  beforeEach(() => {
    // The document comes from the global, and not from `TestBed.inject(DOCUMENT)`:
    // injecting here instantiates the testing module before each case's
    // `configureTestingModule`.
    TestBed.resetTestingModule();
    root = document.documentElement;
    root.removeAttribute('data-theme');
    localStorage.removeItem(THEME_STORAGE_KEY);
  });

  afterEach(() => {
    root.removeAttribute('data-theme');
    localStorage.removeItem(THEME_STORAGE_KEY);
  });

  it('começa no claro quando nada foi escolhido e o sistema não pede escuro', () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: false } as MediaQueryList);

    expect(create().current).toBe('light');
    expect(root.hasAttribute('data-theme')).toBeFalse();
  });

  it('segue a preferência do sistema na primeira visita', () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);

    expect(create().current).toBe('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
  });

  it('a escolha salva vence a preferência do sistema', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light');
    spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);

    expect(create().current).toBe('light');
  });

  /**
   * The `index.html` script paints the theme before Angular boots. If the service decided
   * all over again on its own, the screen would change color mid-bootstrap.
   */
  it('respeita o tema que o script de abertura já aplicou', () => {
    root.setAttribute('data-theme', 'dark');
    spyOn(window, 'matchMedia').and.returnValue({ matches: false } as MediaQueryList);

    expect(create().current).toBe('dark');
  });

  it('alternar troca o atributo do documento e guarda a escolha', () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: false } as MediaQueryList);
    const service = create();

    service.toggle();
    expect(service.current).toBe('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');

    service.toggle();
    expect(service.current).toBe('light');
    expect(root.hasAttribute('data-theme')).toBeFalse();
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
  });

  it('emite a cada troca, para quem acompanha o tema', () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: false } as MediaQueryList);
    const service = create();
    const seen: string[] = [];
    service.theme$.subscribe((theme) => seen.push(theme));

    service.toggle();

    expect(seen).toEqual(['light', 'dark']);
  });

  /** A private window throws on touching storage; the preference is not worth a white screen. */
  it('continua funcionando quando o storage está bloqueado', () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: false } as MediaQueryList);
    spyOn(localStorage, 'getItem').and.throwError('bloqueado');
    spyOn(localStorage, 'setItem').and.throwError('bloqueado');

    const service = create();
    expect(() => service.toggle()).not.toThrow();
    expect(service.current).toBe('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
  });
});
