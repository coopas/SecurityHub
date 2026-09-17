import { Observable, map, skip } from 'rxjs';

import { Theme } from '../../../core/services/theme.service';

/**
 * Cores dos gráficos lidas dos tokens do tema em `styles.scss`, e não duplicadas em
 * hexadecimal aqui: severidade e status precisam sair do gráfico com exatamente a mesma
 * cor dos chips das listagens, e dois lugares definindo a mesma cor divergem no primeiro
 * ajuste de contraste.
 *
 * O valor de reserva existe porque `getComputedStyle` devolve string vazia quando o token
 * não está no documento — em um teste que não carregue `styles.scss`, por exemplo — e um
 * gráfico sem cor nenhuma seria pior do que um com a cor antiga.
 */
export function readThemeColor(token: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
  return value || fallback;
}

/**
 * Tudo o que o Chart.js pinta fora das séries: eixos, grade, legenda e balão.
 *
 * O Chart.js desenha em canvas e não enxerga CSS — sem estes valores ele cai nos cinzas
 * fixos da biblioteca, que somem contra o fundo do tema escuro. É por isso que o cromo
 * também sai dos tokens, e não só as barras e as linhas.
 */
export interface ChartChrome {
  /** Marcações dos eixos e rótulos da legenda. */
  ink: string;
  /** Texto do balão, que fica sobre uma superfície e por isso pede o tom cheio. */
  inkStrong: string;
  /** Linhas horizontais de grade: decoração, deliberadamente fraca. */
  grid: string;
  /** Fundo do balão. */
  surface: string;
  /** Contorno do balão e linha de base dos eixos. */
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
 * Avisa que o gráfico precisa ser repintado, uma vez por troca de tema.
 *
 * `skip(1)`: o `theme$` é um `BehaviorSubject` e entrega o tema atual já na assinatura;
 * esse primeiro valor não é uma troca, e o gráfico é montado logo em seguida com as cores
 * certas de qualquer forma.
 *
 * Ler `getComputedStyle` direto na assinatura é seguro: o `ThemeService` escreve
 * `data-theme` no documento antes de emitir, justamente para que quem reage à troca já
 * encontre os tokens novos. Foi preciso adiar por uma microtarefa enquanto a ordem era a
 * inversa, e o teste de repintura no tema escuro é o que guarda essa garantia.
 */
export function themeRepaints(theme$: Observable<Theme>): Observable<void> {
  return theme$.pipe(
    skip(1),
    map(() => undefined),
  );
}
