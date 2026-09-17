import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';

import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH } from '../../../core/models';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { passwordsMatchValidator } from '../utils/password-match.validator';

@Component({
  selector: 'app-reset-password',
  templateUrl: './reset-password.component.html',
  styleUrls: ['../auth.scss'],
})
export class ResetPasswordComponent implements OnInit {
  readonly minLength = PASSWORD_MIN_LENGTH;
  readonly maxLength = PASSWORD_MAX_LENGTH;

  /**
   * Os limites são os de `PasswordResetConfirmRequest`. Repetidos aqui de propósito: a
   * tela evita uma ida ao servidor para dizer o óbvio, e o servidor continua sendo quem
   * decide — um formulário adulterado esbarra na mesma regra lá.
   */
  readonly form: FormGroup = this.formBuilder.group(
    {
      password: [
        '',
        [
          Validators.required,
          Validators.minLength(PASSWORD_MIN_LENGTH),
          Validators.maxLength(PASSWORD_MAX_LENGTH),
        ],
      ],
      confirmation: ['', [Validators.required]],
    },
    { validators: passwordsMatchValidator },
  );

  token = '';
  submitting = false;
  generalError: string | null = null;
  hidePassword = true;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get hasToken(): boolean {
    return this.token.length > 0;
  }

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';

    if (this.hasToken) {
      // O token é uma credencial de uso único: deixá-lo na barra de endereços o
      // espalharia pelo histórico, pelos favoritos e pelo cabeçalho Referer de qualquer
      // recurso externo que a página viesse a carregar. `replaceUrl` apaga também a
      // entrada do histórico que já o continha.
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: {},
        replaceUrl: true,
      });
    }
  }

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting || !this.hasToken) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.authService
      .confirmPasswordReset({
        token: this.token,
        password: String(this.form.value.password ?? ''),
      })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: () => {
          // A confirmação não abre sessão no backend: entrar é o próximo passo.
          this.notifications.success('Senha redefinida. Entre com a senha nova.');
          void this.router.navigateByUrl('/login');
        },
        error: (error: unknown) => this.handleError(error),
      });
  }

  private handleError(error: unknown): void {
    const apiError = toApiError(error);
    const unmatched = applyFieldErrors(this.form, apiError);
    this.generalError =
      unmatched.length > 0
        ? unmatched.join(' ')
        : apiError?.message ?? 'Não foi possível redefinir a senha. Solicite um link novo.';
  }
}
