import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { finalize } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';

/**
 * Single confirmation text. It is constant on purpose: if the screen said "enviamos" for a
 * registered address and "não encontramos" for another, any visitor could work out who has
 * an account on the platform just by varying the field. The backend answers 202 in both
 * cases; the screen cannot be more specific than it is.
 */
export const PASSWORD_RESET_NEUTRAL_MESSAGE =
  'Se existir uma conta com esse e-mail, enviamos um link de redefinição.';

@Component({
  selector: 'app-forgot-password',
  templateUrl: './forgot-password.component.html',
  styleUrls: ['../auth.scss'],
})
export class ForgotPasswordComponent {
  readonly neutralMessage = PASSWORD_RESET_NEUTRAL_MESSAGE;

  readonly form: FormGroup = this.formBuilder.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(180)]],
  });

  submitting = false;
  submitted = false;
  generalError: string | null = null;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly authService: AuthService,
  ) {}

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.authService
      .requestPasswordReset({ email: String(this.form.value.email ?? '').trim() })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        // Only the 202 gets here, and it is the same for a known and for an unknown e-mail.
        next: () => (this.submitted = true),
        // An infrastructure failure (5xx) is another matter: hiding that nothing was sent
        // would leave the user waiting for an e-mail that will never come.
        error: (error: unknown) => this.handleError(error),
      });
  }

  private handleError(error: unknown): void {
    const apiError = toApiError(error);
    const unmatched = applyFieldErrors(this.form, apiError);
    this.generalError =
      unmatched.length > 0
        ? unmatched.join(' ')
        : apiError?.message ?? 'Não foi possível enviar o link agora. Tente novamente.';
  }
}
