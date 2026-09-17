import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject, finalize, takeUntil } from 'rxjs';

import { ROLE_LABELS, Role, User } from '../../../core/models';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { UserService } from '../services/user.service';

/**
 * Detalhe do usuário. Só o nome é editável aqui, e é o contrato inteiro de
 * `PATCH /users/{id}`: o e-mail não é editável por ser login e canal de recuperação de
 * senha ao mesmo tempo, e papel e situação têm endpoint próprio, com as guardas de
 * último administrador e de auto-desativação — ambos ficam na listagem.
 */
@Component({
  selector: 'app-user-detail',
  templateUrl: './user-detail.component.html',
  styleUrls: ['../users.scss'],
})
export class UserDetailComponent implements OnInit, OnDestroy {
  readonly roleLabels = ROLE_LABELS;

  readonly form: FormGroup = this.formBuilder.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
  });

  user: User | null = null;
  state: ViewState | null = 'loading';
  submitting = false;
  generalError: string | null = null;

  private readonly destroy$ = new Subject<void>();
  private userId = 0;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly userService: UserService,
    private readonly notifications: NotificationService,
    private readonly route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.userId = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isInteger(this.userId) || this.userId <= 0) {
      this.state = 'error';
      return;
    }
    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.state = 'loading';
    this.userService
      .get(this.userId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (user) => {
          this.user = user;
          this.form.patchValue({ name: user.name });
          this.state = null;
        },
        error: () => {
          this.user = null;
          this.state = 'error';
        },
      });
  }

  roleLabel(role: Role): string {
    return this.roleLabels[role];
  }

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting || !this.user) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.userService
      .update(this.userId, { name: String(this.form.value.name ?? '').trim() })
      .pipe(
        takeUntil(this.destroy$),
        finalize(() => (this.submitting = false)),
      )
      .subscribe({
        next: (user) => {
          this.user = user;
          this.form.patchValue({ name: user.name });
          this.notifications.success('Usuário atualizado.');
        },
        error: (error: unknown) => {
          const apiError = toApiError(error);
          const unmatched = applyFieldErrors(this.form, apiError);
          this.generalError =
            unmatched.length > 0
              ? unmatched.join(' ')
              : apiError?.message ?? 'Não foi possível atualizar o usuário.';
        },
      });
  }
}
