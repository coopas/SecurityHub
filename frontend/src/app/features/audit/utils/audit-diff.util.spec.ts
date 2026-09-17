import { makeVulnerabilitySnapshot } from '../testing/audit-test-utils';
import { AuditDiff, buildAuditDiff, formatValue, readAuditSide } from './audit-diff.util';

describe('audit-diff.util', () => {
  describe('readAuditSide', () => {
    it('trata ausência, nulo e objeto vazio como lado ausente', () => {
      expect(readAuditSide(undefined)).toEqual({ kind: 'absent' });
      expect(readAuditSide(null)).toEqual({ kind: 'absent' });
      expect(readAuditSide({})).toEqual({ kind: 'absent' });
      expect(readAuditSide('   ')).toEqual({ kind: 'absent' });
    });

    it('aceita o objeto já desserializado que a API entrega', () => {
      expect(readAuditSide({ status: 'OPEN' })).toEqual({
        kind: 'values',
        values: { status: 'OPEN' },
      });
    });

    it('parseia uma string JSON de objeto', () => {
      expect(readAuditSide('{"status":"OPEN"}')).toEqual({
        kind: 'values',
        values: { status: 'OPEN' },
      });
    });

    it('devolve texto bruto para JSON truncado, sem lançar', () => {
      const truncated = '{"title":"SQL Injection no lo';

      expect(() => readAuditSide(truncated)).not.toThrow();
      expect(readAuditSide(truncated)).toEqual({ kind: 'raw', text: truncated });
    });

    it('devolve texto bruto para JSON que não é um objeto de campos', () => {
      expect(readAuditSide('[1,2,3]')).toEqual({ kind: 'raw', text: '[1,2,3]' });
      expect(readAuditSide(42)).toEqual({ kind: 'raw', text: '42' });
    });
  });

  describe('buildAuditDiff', () => {
    it('LOGIN e REGISTER: nulo dos dois lados é um estado legítimo, não um erro', () => {
      expect(buildAuditDiff(undefined, undefined)).toEqual({ kind: 'empty' });
      expect(buildAuditDiff(null, null)).toEqual({ kind: 'empty' });
    });

    it('CREATE: só o lado novo, sem painel "antes"', () => {
      const diff = buildAuditDiff(undefined, { title: 'Nova', severity: 'HIGH' });

      expect(diff.kind).toBe('created');
      const fields = (diff as Extract<AuditDiff, { kind: 'created' }>).fields;
      expect(fields.map((row) => row.field)).toEqual(['title', 'severity']);
      expect(fields[0]).toEqual(
        jasmine.objectContaining({ label: 'Título', oldText: null, newText: 'Nova' }),
      );
    });

    it('DELETE: só o lado antigo, sem painel "depois"', () => {
      const diff = buildAuditDiff({ title: 'Removida' }, undefined);

      expect(diff.kind).toBe('deleted');
      const fields = (diff as Extract<AuditDiff, { kind: 'deleted' }>).fields;
      expect(fields[0]).toEqual(
        jasmine.objectContaining({ label: 'Título', oldText: 'Removida', newText: null }),
      );
    });

    it('UPDATE: separa o que mudou do que ficou igual', () => {
      const before = makeVulnerabilitySnapshot();
      const after = makeVulnerabilitySnapshot({ severity: 'CRITICAL' });

      const diff = buildAuditDiff(before, after);

      expect(diff.kind).toBe('updated');
      const updated = diff as Extract<AuditDiff, { kind: 'updated' }>;
      expect(updated.changed.length).toBe(1);
      expect(updated.changed[0]).toEqual(
        jasmine.objectContaining({
          field: 'severity',
          label: 'Severidade',
          oldText: 'HIGH',
          newText: 'CRITICAL',
          changed: true,
        }),
      );
      expect(updated.unchanged.length).toBe(Object.keys(before).length - 1);
      expect(updated.unchanged.every((row) => !row.changed)).toBeTrue();
    });

    it('chave ausente de um lado conta como mudança de e para vazio', () => {
      const diff = buildAuditDiff(
        { status: 'OPEN' },
        { status: 'RESOLVED', resolvedAt: '2026-09-17T12:00:00Z' },
      ) as Extract<AuditDiff, { kind: 'updated' }>;

      expect(diff.changed.map((row) => row.field)).toEqual(['status', 'resolvedAt']);
      expect(diff.changed[1].oldText).toBeNull();
      expect(diff.changed[1].newText).toBe('2026-09-17T12:00:00Z');
    });

    it('preserva a ordem do backend e acrescenta as chaves só do lado novo ao final', () => {
      const diff = buildAuditDiff(
        { name: 'a', description: 'b' },
        { name: 'a', description: 'b', status: 'ACTIVE' },
      ) as Extract<AuditDiff, { kind: 'updated' }>;

      expect([...diff.changed, ...diff.unchanged].length).toBe(3);
      expect(diff.unchanged.map((row) => row.field)).toEqual(['name', 'description']);
      expect(diff.changed.map((row) => row.field)).toEqual(['status']);
    });

    it('um lado ilegível derruba a comparação campo a campo para texto bruto', () => {
      const diff = buildAuditDiff('{"title":"cortad', { title: 'Nova' });

      expect(diff.kind).toBe('raw');
      const raw = diff as Extract<AuditDiff, { kind: 'raw' }>;
      expect(raw.oldText).toBe('{"title":"cortad');
      expect(raw.newText).toContain('"title": "Nova"');
    });

    it('não lança para nenhum formato inesperado', () => {
      const inputs: unknown[] = [0, '', [], true, 'texto solto', { a: { b: [1, 2] } }];

      for (const left of inputs) {
        for (const right of inputs) {
          expect(() => buildAuditDiff(left, right)).not.toThrow();
        }
      }
    });
  });

  describe('formatValue', () => {
    it('devolve texto para escalares e JSON legível para estruturas', () => {
      expect(formatValue('texto')).toBe('texto');
      expect(formatValue(8.1)).toBe('8.1');
      expect(formatValue(false)).toBe('false');
      expect(formatValue({ a: 1 })).toBe('{\n  "a": 1\n}');
    });

    it('não escapa nem interpreta marcação: o valor é devolvido como texto', () => {
      const payload = '<img src=x onerror="alert(1)">';

      // Escapar aqui seria duplicar o trabalho do Angular; o que importa é que o valor
      // nunca vira HTML — o template só o interpola.
      expect(formatValue(payload)).toBe(payload);
    });

    it('sobrevive a uma referência circular', () => {
      const circular: Record<string, unknown> = {};
      circular['self'] = circular;

      expect(() => formatValue(circular)).not.toThrow();
    });
  });
});
