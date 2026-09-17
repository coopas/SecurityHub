/**
 * Duas funções puras para respostas de download (`responseType: 'blob'`): descobrir o
 * nome do arquivo no `Content-Disposition` e entregá-lo ao navegador.
 *
 * Vivem aqui, e não dentro de um serviço, porque nada nelas depende de Angular: são
 * usadas pela exportação em CSV, pelo download de anexos e pelo relatório executivo.
 */

/**
 * `filename*=UTF-8''nome%20com%20acento.csv`, a forma da RFC 5987. Os três grupos são
 * charset, idioma (quase sempre vazio) e o valor percent-encoded.
 */
const EXTENDED_FILENAME = /filename\*\s*=\s*([^']*)'([^']*)'([^;]*)/i;

/** `filename="nome.csv"` ou `filename=nome.csv`, a forma antiga. */
const PLAIN_FILENAME = /filename\s*=\s*(?:"([^"]*)"|([^;]*))/i;

/**
 * Nome do arquivo anunciado pelo servidor, ou `fallback` quando o cabeçalho não traz um
 * nome aproveitável.
 *
 * `filename*` tem precedência sobre `filename` porque é a única forma que carrega o
 * charset: os servidores que mandam as duas repetem em `filename` uma versão degradada,
 * sem acentos, para clientes antigos. O valor estendido é percent-encoded e por isso
 * passa por `decodeURIComponent`.
 */
export function filenameFromContentDisposition(header: string | null, fallback: string): string {
  if (!header) {
    return fallback;
  }
  const parsed = extendedFilename(header) ?? plainFilename(header);
  return parsed !== null && isSafeFilename(parsed) ? parsed : fallback;
}

/**
 * Entrega o blob ao navegador como um download com o nome informado.
 *
 * Duas sutilezas do Firefox, ambas silenciosas no Chrome, que é onde o erro passaria
 * despercebido:
 *
 * - a âncora precisa estar no documento no momento do clique (uma âncora solta não
 *   dispara nada), e é removida no mesmo tique para não deixar lixo no DOM;
 * - revogar a URL de objeto de forma síncrona cancela o download que acabou de começar,
 *   então a revogação vai para um `setTimeout(..., 0)`. Sem revogar, o blob ficaria
 *   retido até a página ser descarregada.
 */
export function saveBlob(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.style.display = 'none';

  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();

  setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
}

function extendedFilename(header: string): string | null {
  const match = EXTENDED_FILENAME.exec(header);
  if (!match) {
    return null;
  }
  try {
    return decodeURIComponent(match[3].trim());
  } catch {
    // Percent-encoding malformado: o cabeçalho não serve, e o chamador usa o fallback.
    return null;
  }
}

function plainFilename(header: string): string | null {
  const match = PLAIN_FILENAME.exec(header);
  if (!match) {
    return null;
  }
  const value = (match[1] ?? match[2] ?? '').trim();
  return value || null;
}

/**
 * Hoje quem gera o nome é o próprio backend, mas esta função é genérica e o cabeçalho é
 * o único ponto em que um nome de origem hostil chegaria ao sistema de arquivos do
 * usuário. Barra, contrabarra e caracteres de controle saem de cena: são o que
 * transformaria um nome em um caminho (ou em um nome que o gerenciador de downloads
 * interpreta). Qualquer nome recusado vira o fallback, que é sempre do nosso lado.
 */
function isSafeFilename(filename: string): boolean {
  if (filename.length === 0 || filename.includes('/') || filename.includes('\\')) {
    return false;
  }
  // C0 e DEL, escritos em código e não como regex: um literal com caracteres de
  // controle é ilegível e a própria regra de lint o recusa.
  for (const character of filename) {
    const code = character.charCodeAt(0);
    if (code < 0x20 || code === 0x7f) {
      return false;
    }
  }
  return true;
}
