import { DOCUMENT } from '@angular/common';
import { Inject, Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export const THEME_STORAGE_KEY = 'securityhub.theme';

export type Theme = 'light' | 'dark';

/**
 * Tema da interface, com a escolha guardada por navegador.
 *
 * <p>O tema inicial já foi aplicado por um script no `index.html`, antes do Angular subir,
 * para não piscar branco a cada visita de quem usa o escuro. Este serviço lê o que aquele
 * script deixou no documento em vez de decidir de novo — duas fontes de verdade para a
 * mesma pergunta acabariam divergindo.
 *
 * <p>É preferência de exibição, não dado de negócio: fica no `localStorage`, não viaja para
 * a API e não acompanha o usuário entre máquinas. Quem nunca escolheu segue a preferência
 * do sistema operacional, e passa a ser respeitada a escolha explícita assim que houver uma.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly themeSubject: BehaviorSubject<Theme>;

  readonly theme$: Observable<Theme>;

  constructor(@Inject(DOCUMENT) private readonly document: Document) {
    this.themeSubject = new BehaviorSubject<Theme>(this.detectInitialTheme());
    this.theme$ = this.themeSubject.asObservable();
    this.apply(this.themeSubject.value);
  }

  get current(): Theme {
    return this.themeSubject.value;
  }

  toggle(): void {
    this.set(this.current === 'dark' ? 'light' : 'dark');
  }

  /**
   * O atributo vai para o documento **antes** do `next`. Quem assina `theme$` costuma
   * reagir lendo token com `getComputedStyle` — é o caso dos gráficos, que se repintam —
   * e emitir primeiro entregaria a esse assinante os valores do tema que está saindo,
   * deixando a tela um tema atrasada.
   */
  set(theme: Theme): void {
    this.apply(theme);
    this.remember(theme);
    this.themeSubject.next(theme);
  }

  private apply(theme: Theme): void {
    const root = this.document.documentElement;
    if (theme === 'dark') {
      root.setAttribute('data-theme', 'dark');
    } else {
      root.removeAttribute('data-theme');
    }
  }

  /**
   * A ordem importa: o que o script de abertura já pintou vence, depois o que foi salvo e,
   * por último, a preferência do sistema.
   */
  private detectInitialTheme(): Theme {
    if (this.document.documentElement.getAttribute('data-theme') === 'dark') {
      return 'dark';
    }

    const saved = this.read();
    if (saved) {
      return saved;
    }

    const media = this.document.defaultView?.matchMedia('(prefers-color-scheme: dark)');
    return media?.matches ? 'dark' : 'light';
  }

  /**
   * O acesso ao storage é protegido nos dois sentidos: em janela privativa ele lança em vez
   * de devolver vazio, e derrubar a aplicação inteira por causa de uma preferência de cor
   * seria trocar um incômodo por uma falha.
   */
  private read(): Theme | null {
    try {
      const value = this.document.defaultView?.localStorage.getItem(THEME_STORAGE_KEY);
      return value === 'dark' || value === 'light' ? value : null;
    } catch {
      return null;
    }
  }

  private remember(theme: Theme): void {
    try {
      this.document.defaultView?.localStorage.setItem(THEME_STORAGE_KEY, theme);
    } catch {
      /* Sem storage a escolha vale só para esta aba, o que ainda é melhor que falhar. */
    }
  }
}
