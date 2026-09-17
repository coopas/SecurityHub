import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export const PASSWORD_CONTROL = 'password';
export const PASSWORD_CONFIRMATION_CONTROL = 'confirmation';

/**
 * Equality between the password and its confirmation. The error is written onto the
 * confirmation control, and not only onto the group: `mat-error` only shows up when the
 * field's own control is invalid, so an error that lived only on the group would never be
 * displayed next to the field the user has to fix.
 *
 * Nothing is written while the confirmation is empty: whoever has not typed yet has not
 * made a mistake.
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

  // Only rewrites when the verdict changed: `setErrors` propagates status up to the group
  // and repeating it every round would be pure busywork.
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
