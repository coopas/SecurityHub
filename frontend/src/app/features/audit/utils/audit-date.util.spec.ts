import {
  endOfDayInstant,
  parseCivilDate,
  startOfDayInstant,
  toCivilDate,
  toLocalDate,
} from './audit-date.util';

describe('audit-date.util', () => {
  it('converte a data escolhida usando o fuso do usuário, não UTC', () => {
    // 21h em UTC-3 já é o dia seguinte em UTC; o rótulo tem de continuar sendo o dia
    // que a pessoa marcou no calendário.
    const lateEvening = new Date(2026, 8, 17, 21, 30, 0, 0);

    expect(toCivilDate(lateEvening)).toBe('2026-09-17');
  });

  it('aceita apenas datas civis existentes vindas da URL', () => {
    expect(parseCivilDate('2026-09-17')).toBe('2026-09-17');
    expect(parseCivilDate(' 2026-09-17 ')).toBe('2026-09-17');
    expect(parseCivilDate('2026-02-31')).toBeNull();
    expect(parseCivilDate('17/09/2026')).toBeNull();
    expect(parseCivilDate('ontem')).toBeNull();
    expect(parseCivilDate(null)).toBeNull();
  });

  it('monta a data local à meia-noite para o calendário', () => {
    const date = toLocalDate('2026-09-17');

    expect(date?.getFullYear()).toBe(2026);
    expect(date?.getMonth()).toBe(8);
    expect(date?.getDate()).toBe(17);
    expect(date?.getHours()).toBe(0);
    expect(toLocalDate('lixo')).toBeNull();
  });

  it('from cobre o início do dia local', () => {
    const instant = startOfDayInstant('2026-09-17');
    const parsed = new Date(instant as string);

    expect(instant).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect(parsed.getHours()).toBe(0);
    expect(parsed.getMinutes()).toBe(0);
    expect(parsed.getSeconds()).toBe(0);
    expect(toCivilDate(parsed)).toBe('2026-09-17');
  });

  it('to cobre o dia inteiro, e não a meia-noite do seu início', () => {
    const instant = endOfDayInstant('2026-09-17');
    const parsed = new Date(instant as string);

    expect(parsed.getHours()).toBe(23);
    expect(parsed.getMinutes()).toBe(59);
    expect(parsed.getSeconds()).toBe(59);
    expect(parsed.getMilliseconds()).toBe(999);
    expect(toCivilDate(parsed)).toBe('2026-09-17');
  });

  it('o intervalo de um único dia vai do primeiro ao último instante desse dia', () => {
    const from = new Date(startOfDayInstant('2026-09-17') as string);
    const to = new Date(endOfDayInstant('2026-09-17') as string);

    expect(from.getTime()).toBeLessThan(to.getTime());
    expect(toCivilDate(from)).toBe('2026-09-17');
    expect(toCivilDate(to)).toBe('2026-09-17');
    // Um milissegundo depois já é o dia seguinte: o `to` é mesmo o último instante.
    expect(toCivilDate(new Date(to.getTime() + 1))).toBe('2026-09-18');
  });

  it('descarta datas inválidas em vez de mandar um instante inválido ao servidor', () => {
    expect(startOfDayInstant('2026-13-01')).toBeNull();
    expect(endOfDayInstant('não é data')).toBeNull();
  });
});
