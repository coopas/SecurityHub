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
