import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { finalize } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';

/**
 * Texto único da confirmação. É constante de propósito: se a tela dissesse "enviamos"
 * para um endereço cadastrado e "não encontramos" para outro, qualquer visitante
 * descobriria quem tem conta na plataforma apenas variando o campo. O backend responde
 * 202 nos dois casos; a tela não pode ser mais específica do que ele.
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
        // Só o 202 chega aqui, e ele é o mesmo para e-mail conhecido e desconhecido.
        next: () => (this.submitted = true),
        // Uma falha de infraestrutura (5xx) é outra coisa: esconder que nada foi enviado
        // deixaria o usuário esperando um e-mail que não virá.
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
