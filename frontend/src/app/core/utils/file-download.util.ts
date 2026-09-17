/**
 * Two pure functions for download responses (`responseType: 'blob'`): finding the file
 * name in the `Content-Disposition` and handing it to the browser.
 *
 * They live here, and not inside a service, because nothing in them depends on Angular:
 * they are used by the CSV export, by the attachment download and by the executive report.
 */

/**
 * `filename*=UTF-8''nome%20com%20acento.csv`, the RFC 5987 form. The three groups are
 * charset, language (almost always empty) and the percent-encoded value.
 */
const EXTENDED_FILENAME = /filename\*\s*=\s*([^']*)'([^']*)'([^;]*)/i;

/** `filename="nome.csv"` or `filename=nome.csv`, the old form. */
const PLAIN_FILENAME = /filename\s*=\s*(?:"([^"]*)"|([^;]*))/i;

/**
 * The file name announced by the server, or `fallback` when the header carries no usable
 * name.
 *
 * `filename*` takes precedence over `filename` because it is the only form that carries
 * the charset: the servers that send both repeat in `filename` a degraded version, with no
 * accents, for old clients. The extended value is percent-encoded and therefore goes
 * through `decodeURIComponent`.
 */
export function filenameFromContentDisposition(header: string | null, fallback: string): string {
  if (!header) {
    return fallback;
  }
  const parsed = extendedFilename(header) ?? plainFilename(header);
  return parsed !== null && isSafeFilename(parsed) ? parsed : fallback;
}

/**
 * Hands the blob to the browser as a download under the given name.
 *
 * Two Firefox subtleties, both silent in Chrome, which is where the mistake would go
 * unnoticed:
 *
 * - the anchor has to be in the document at the moment of the click (a detached anchor
 *   fires nothing), and it is removed on the same tick so as not to leave junk in the DOM;
 * - revoking the object URL synchronously cancels the download that has just started, so
 *   the revocation goes into a `setTimeout(..., 0)`. Without revoking, the blob would stay
 *   retained until the page is unloaded.
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
    // Malformed percent-encoding: the header is no good, and the caller uses the fallback.
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
 * Today it is the backend itself that generates the name, but this function is generic and
 * the header is the only point at which a hostile-origin name would reach the user's file
 * system. Slash, backslash and control characters are off the table: they are what would
 * turn a name into a path (or into a name the download manager interprets). Any refused
 * name becomes the fallback, which is always on our side.
 */
function isSafeFilename(filename: string): boolean {
  if (filename.length === 0 || filename.includes('/') || filename.includes('\\')) {
    return false;
  }
  // C0 and DEL, written in code and not as a regex: a literal with control characters
  // is unreadable and the lint rule itself refuses it.
  for (const character of filename) {
    const code = character.charCodeAt(0);
    if (code < 0x20 || code === 0x7f) {
      return false;
    }
  }
  return true;
}
