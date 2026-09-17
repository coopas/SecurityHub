import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export const PASSWORD_CONTROL = 'password';
export const PASSWORD_CONFIRMATION_CONTROL = 'confirmation';

/**
 * Igualdade entre a senha e a confirmação. O erro é escrito no controle de confirmação,
 * e não apenas no grupo: `mat-error` só aparece quando o próprio controle do campo está
 * inválido, então um erro que vivesse só no grupo nunca seria exibido ao lado do campo
 * que o usuário precisa corrigir.
 *
 * Nada é escrito enquanto a confirmação está vazia: quem ainda não digitou não errou.
 */
export const passwordsMatchValidator: ValidatorFn = (
  group: AbstractControl,
): ValidationErrors | null => {
  const password = group.get(PASSWORD_CONTROL);
  const confirmation = group.get(PASSWORD_CONFIRMATION_CONTROL);
  if (!password || !confirmation) {
    return null;
  }

  const mismatch = !!confirmation.value && password.value !== confirmation.value;
  const errors = { ...(confirmation.errors ?? {}) };
  const marked = 'passwordMismatch' in errors;

  // Só reescreve quando o veredito mudou: `setErrors` propaga status para o grupo e
  // repeti-lo a cada rodada seria trabalho puro.
  if (mismatch !== marked) {
    if (mismatch) {
      errors['passwordMismatch'] = true;
    } else {
      delete errors['passwordMismatch'];
    }
    confirmation.setErrors(Object.keys(errors).length > 0 ? errors : null);
  }

  return mismatch ? { passwordMismatch: true } : null;
};
