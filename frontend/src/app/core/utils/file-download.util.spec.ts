import { filenameFromContentDisposition, saveBlob } from './file-download.util';

describe('file-download.util', () => {
  describe('filenameFromContentDisposition', () => {
    it('prefere filename* e decodifica o percent-encoding da RFC 5987', () => {
      const header = "attachment; filename=vulnerabilidades.csv; filename*=UTF-8''vulnerabilidades-2026-09-17.csv";

      expect(filenameFromContentDisposition(header, 'fallback.csv')).toBe(
        'vulnerabilidades-2026-09-17.csv',
      );
    });

    it('recupera os acentos que só o filename* carrega', () => {
      // The same header the backend builds with ContentDisposition.filename(nome, UTF_8).
      const header = "attachment; filename*=UTF-8''relat%C3%B3rio%20executivo.pdf";

      expect(filenameFromContentDisposition(header, 'fallback.pdf')).toBe(
        'relatório executivo.pdf',
      );
    });

    it('aceita o filename simples, com e sem aspas', () => {
      expect(filenameFromContentDisposition('attachment; filename="anexo.png"', 'fallback')).toBe(
        'anexo.png',
      );
      expect(filenameFromContentDisposition('attachment; filename=anexo.png', 'fallback')).toBe(
        'anexo.png',
      );
    });

    it('usa o fallback quando o cabeçalho falta, está vazio ou não traz nome', () => {
      expect(filenameFromContentDisposition(null, 'fallback.csv')).toBe('fallback.csv');
      expect(filenameFromContentDisposition('', 'fallback.csv')).toBe('fallback.csv');
      expect(filenameFromContentDisposition('attachment', 'fallback.csv')).toBe('fallback.csv');
      expect(filenameFromContentDisposition('attachment; filename=""', 'fallback.csv')).toBe(
        'fallback.csv',
      );
    });

    it('recusa nome com barra, contrabarra ou caractere de controle', () => {
      expect(
        filenameFromContentDisposition('attachment; filename="../../etc/passwd"', 'fallback.csv'),
      ).toBe('fallback.csv');
      expect(
        filenameFromContentDisposition("attachment; filename*=UTF-8''..%2F..%2Fetc%2Fpasswd", 'fallback.csv'),
      ).toBe('fallback.csv');
      expect(
        filenameFromContentDisposition('attachment; filename="c:\\windows\\system32"', 'fallback.csv'),
      ).toBe('fallback.csv');
      expect(
        filenameFromContentDisposition("attachment; filename*=UTF-8''nome%0Aquebrado.csv", 'fallback.csv'),
      ).toBe('fallback.csv');
    });

    it('usa o fallback quando o percent-encoding do filename* é inválido', () => {
      expect(filenameFromContentDisposition("attachment; filename*=UTF-8''nome%zz.csv", 'fallback.csv')).toBe(
        'fallback.csv',
      );
    });
  });

  describe('saveBlob', () => {
    let createObjectURL: jasmine.Spy<(blob: Blob) => string>;
    let revokeObjectURL: jasmine.Spy<(url: string) => void>;

    beforeEach(() => {
      jasmine.clock().install();
      createObjectURL = spyOn(URL, 'createObjectURL').and.returnValue('blob:objeto');
      revokeObjectURL = spyOn(URL, 'revokeObjectURL');
    });

    afterEach(() => jasmine.clock().uninstall());

    it('clica em uma âncora presente no documento e a remove no mesmo tique', () => {
      const blob = new Blob(['id,titulo\n'], { type: 'text/csv' });
      const anchors: HTMLAnchorElement[] = [];
      const connected: boolean[] = [];
      const click = spyOn(HTMLAnchorElement.prototype, 'click').and.callFake(function (
        this: HTMLAnchorElement,
      ) {
        anchors.push(this);
        // An anchor outside the document fires no download at all in Firefox.
        connected.push(this.isConnected);
      });

      saveBlob(blob, 'vulnerabilidades.csv');

      expect(createObjectURL).toHaveBeenCalledWith(blob);
      expect(click).toHaveBeenCalledTimes(1);
      expect(connected).toEqual([true]);
      expect(anchors[0].download).toBe('vulnerabilidades.csv');
      expect(anchors[0].getAttribute('href')).toBe('blob:objeto');
      // And nothing is left behind in the DOM after the call.
      expect(anchors[0].isConnected).toBeFalse();
    });

    it('revoga a URL do objeto fora do tique do clique', () => {
      spyOn(HTMLAnchorElement.prototype, 'click');

      saveBlob(new Blob(['x']), 'arquivo.csv');

      // Revoking synchronously would cancel the just-started download in Firefox.
      expect(revokeObjectURL).not.toHaveBeenCalled();

      jasmine.clock().tick(0);

      expect(revokeObjectURL).toHaveBeenCalledWith('blob:objeto');
    });
  });
});
