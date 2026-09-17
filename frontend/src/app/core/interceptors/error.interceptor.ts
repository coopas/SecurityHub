import { HttpErrorResponse, HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable, Injector } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ApiError } from '../models';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

const NETWORK_ERROR_MESSAGE = 'Servidor indisponível. Verifique sua conexão e tente novamente.';
const SESSION_EXPIRED_MESSAGE = 'Sua sessão expirou. Entre novamente para continuar.';
const GENERIC_ERROR_MESSAGE = 'Não foi possível concluir a operação. Tente novamente.';

/**
 * Tratamento central de erros HTTP. Sempre relança o erro para que a tela
 * possa reagir (por exemplo, exibindo `fieldErrors` nos campos do formulário).
 */
@Injectable()
export class ErrorInterceptor implements HttpInterceptor {
  constructor(private readonly injector: Injector) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    return next.handle(request).pipe(
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse) {
          this.handle(error, request);
        }
        return throwError(() => error);
      }),
    );
  }

  private handle(error: HttpErrorResponse, request: HttpRequest<unknown>): void {
    const apiError = this.asApiError(error);

    if (error.status === 0) {
      this.notifications.error(NETWORK_ERROR_MESSAGE);
      return;
    }

    if (error.status === 401) {
      // Falha de credencial na própria tela de autenticação não derruba sessão alguma.
      if (this.isAuthEndpoint(request.url)) {
        this.notifications.error(apiError?.message ?? GENERIC_ERROR_MESSAGE);
        return;
      }
      this.expireSession();
      return;
    }

    if (error.status === 403) {
      void this.router.navigate(['/403']);
      return;
    }

    // Erros de validação são exibidos inline pelos formulários.
    if (apiError?.code === 'VALIDATION_ERROR') {
      return;
    }

    this.notifications.error(apiError?.message ?? GENERIC_ERROR_MESSAGE);
  }

  /** Sem refresh token no MVP: 401 significa limpar a sessão e voltar ao login. */
  private expireSession(): void {
    const returnUrl = this.router.url;
    this.authService.logout();
    this.notifications.error(SESSION_EXPIRED_MESSAGE);
    void this.router.navigate(['/login'], {
      queryParams: returnUrl && !returnUrl.startsWith('/login') ? { returnUrl } : {},
    });
  }

  private asApiError(error: HttpErrorResponse): ApiError | null {
    const body: unknown = error.error;
    if (body && typeof body === 'object' && 'code' in body && 'message' in body) {
      return body as ApiError;
    }
    return null;
  }

  private isAuthEndpoint(url: string): boolean {
    return (
      url.startsWith(`${environment.apiUrl}/auth/login`) ||
      url.startsWith(`${environment.apiUrl}/auth/register`)
    );
  }

  // Resolvidos sob demanda para evitar dependência cíclica com o HttpClient.
  private get authService(): AuthService {
    return this.injector.get(AuthService);
  }

  private get notifications(): NotificationService {
    return this.injector.get(NotificationService);
  }

  private get router(): Router {
    return this.injector.get(Router);
  }
}
