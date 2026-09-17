import { Observable, map, skip } from 'rxjs';

import { Theme } from '../../../core/services/theme.service';

/**
 * Chart colors read from the theme tokens in `styles.scss`, and not duplicated in hex here:
 * severity and status have to come out of the chart in exactly the same color as the chips
 * in the lists, and two places defining the same color diverge at the first contrast
 * adjustment.
 *
 * The fallback value exists because `getComputedStyle` returns an empty string when the
 * token is not in the document — in a test that does not load `styles.scss`, for example —
 * and a chart with no color at all would be worse than one with the old color.
 */
export function readThemeColor(token: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
  return value || fallback;
}

/**
 * Everything Chart.js paints outside the series: axes, grid, legend and tooltip.
 *
 * Chart.js draws on canvas and cannot see CSS — without these values it falls back to the
 * library's hard-coded grays, which vanish against the dark theme's background. That is why
 * the chrome also comes out of the tokens, and not just the bars and the lines.
 */
export interface ChartChrome {
  /** Axis ticks and legend labels. */
  ink: string;
  /** Tooltip text, which sits on a surface and therefore asks for the full shade. */
  inkStrong: string;
  /** Horizontal grid lines: decoration, deliberately faint. */
  grid: string;
  /** Tooltip background. */
  surface: string;
  /** Tooltip border and the baseline of the axes. */
  border: string;
}

export function readChartChrome(): ChartChrome {
  return {
    ink: readThemeColor('--sh-text-muted', '#475569'),
    inkStrong: readThemeColor('--sh-text', '#0f172a'),
    grid: readThemeColor('--sh-border', '#e2e8f0'),
    surface: readThemeColor('--sh-surface', '#ffffff'),
    border: readThemeColor('--sh-border-strong', '#75879c'),
  };
}

/**
 * Signals that the chart needs repainting, once per theme switch.
 *
 * `skip(1)`: `theme$` is a `BehaviorSubject` and hands over the current theme on the
 * subscription itself; that first value is not a switch, and the chart is built right
 * afterwards with the right colors anyway.
 *
 * Reading `getComputedStyle` straight in the subscription is safe: `ThemeService` writes
 * `data-theme` on the document before emitting, precisely so that whoever reacts to the
 * switch already finds the new tokens. Deferring by a microtask was necessary while the
 * order was the other way around, and the dark theme repaint test is what guards this
 * guarantee.
 */
export function themeRepaints(theme$: Observable<Theme>): Observable<void> {
  return theme$.pipe(
    skip(1),
    map(() => undefined),
  );
}
