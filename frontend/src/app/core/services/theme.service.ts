import { DOCUMENT } from '@angular/common';
import { Inject, Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export const THEME_STORAGE_KEY = 'securityhub.theme';

export type Theme = 'light' | 'dark';

/**
 * The interface theme, with the choice stored per browser.
 *
 * <p>The initial theme has already been applied by a script in `index.html`, before Angular
 * boots, so as not to flash white on every visit for whoever uses dark. This service reads
 * what that script left on the document instead of deciding all over again — two sources of
 * truth for the same question would end up diverging.
 *
 * <p>It is a display preference, not business data: it lives in `localStorage`, does not
 * travel to the API and does not follow the user between machines. Whoever never chose
 * follows the operating system preference, and the explicit choice is honoured once there is one.
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
   * The attribute goes onto the document **before** the `next`. Whoever subscribes to
   * `theme$` tends to react by reading tokens with `getComputedStyle` — the charts do, and
   * they repaint — and emitting first would hand that subscriber the values of the theme on
   * its way out, leaving the screen one theme behind.
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
   * The order matters: what the opening script already painted wins, then what was saved
   * and, last of all, the system preference.
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
   * Storage access is guarded in both directions: in a private window it throws instead of
   * returning empty, and bringing the whole application down over a color preference would
   * be trading a nuisance for a failure.
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
      /* With no storage the choice holds only for this tab, which still beats failing. */
    }
  }
}
