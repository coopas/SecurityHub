import { HttpErrorResponse, HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable, Injector } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, shareReplay, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ApiError, AuthResponse } from '../models';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

const NETWORK_ERROR_MESSAGE = 'Servidor indisponível. Verifique sua conexão e tente novamente.';
const SESSION_EXPIRED_MESSAGE = 'Sua sessão expirou. Entre novamente para continuar.';
const GENERIC_ERROR_MESSAGE = 'Não foi possível concluir a operação. Tente novamente.';

/**
 * Tratamento central de erros HTTP e único lugar que renova a sessão.
 *
 * Um 401 em rota autenticada dispara uma renovação e repete a requisição original; só
 * quando a renovação falha é que a sessão cai. Sempre relança o erro para que a tela
 * possa reagir (por exemplo, exibindo `fieldErrors` nos campos do formulário).
 */
@Injectable()
export class ErrorInterceptor implements HttpInterceptor {
  /**
   * Renovação em voo, compartilhada por todos os 401 da mesma rajada. `refCount: false`
   * é deliberado: com `true`, a primeira requisição a desistir cancelaria o POST de
   * renovação e as demais ficariam esperando uma resposta que ninguém mais pediu.
   */
  private refresh$: Observable<AuthResponse> | null = null;

  constructor(private readonly injector: Injector) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    return next.handle(request).pipe(
      catchError((error: unknown) => {
        if (!(error instanceof HttpErrorResponse)) {
          return throwError(() => error);
        }

        if (this.shouldRefresh(error, request)) {
          return this.refreshAndRetry(request, next, error);
        }

        this.handle(error, request);
        return throwError(() => error);
      }),
    );
  }

  /**
   * Renova uma vez e repete a requisição.
   *
   * A repetição acontece aqui dentro, depois que o `catchError` externo já disparou:
   * um erro da segunda tentativa cai no tratamento interno e não volta a entrar em
   * `intercept`, de modo que não existe laço de 401 renovando para sempre.
   */
  private refreshAndRetry(
    request: HttpRequest<unknown>,
    next: HttpHandler,
    original: HttpErrorResponse,
  ): Observable<HttpEvent<unknown>> {
    return this.sharedRefresh().pipe(
      catchError(() => {
        // A renovação era a última chance da sessão; o refresh token também já morreu.
        this.expireSession();
        return throwError(() => original);
      }),
      switchMap((response) =>
        // O `AuthInterceptor` roda antes deste e não verá a requisição repetida: sem
        // este clone ela levaria de volta o access token vencido.
        next
          .handle(request.clone({ setHeaders: { Authorization: `Bearer ${response.accessToken}` } }))
          .pipe(
            catchError((retryError: unknown) => {
              if (retryError instanceof HttpErrorResponse) {
                this.handle(retryError, request);
              }
              return throwError(() => retryError);
            }),
          ),
      ),
    );
  }

  /**
   * Uma renovação por rajada. O `finalize` zera a referência quando ela termina, de modo
   * que o próximo 401 comece uma renovação nova em vez de reviver o resultado antigo.
   */
  private sharedRefresh(): Observable<AuthResponse> {
    if (!this.refresh$) {
      this.refresh$ = this.authService.refresh().pipe(
        finalize(() => (this.refresh$ = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.refresh$;
  }

  /**
   * Só vale a pena renovar quando há o que renovar e a requisição é da própria API —
   * anexar um bearer token a uma origem estranha seria vazá-lo. Sem refresh token
   * guardado, o 401 segue o caminho de sempre: encerrar a sessão.
   */
  private shouldRefresh(error: HttpErrorResponse, request: HttpRequest<unknown>): boolean {
    return (
      error.status === 401 &&
      request.url.startsWith(environment.apiUrl) &&
      !this.isAuthEndpoint(request.url) &&
      this.authService.refreshToken !== null
    );
  }

  private handle(error: HttpErrorResponse, request: HttpRequest<unknown>): void {
    const apiError = this.asApiError(error);

    if (error.status === 0) {
      this.notifications.error(NETWORK_ERROR_MESSAGE);
      return;
    }

    if (error.status === 401) {
      // O 401 da própria renovação é decidido por `refreshAndRetry`, que já encerra a
      // sessão e avisa uma vez. Avisar aqui também mostraria dois snackbars do mesmo evento.
      if (this.isRefreshEndpoint(request.url)) {
        return;
      }
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

    // Numa requisição `responseType: 'blob'` o corpo de erro também chega como Blob, que
    // `asApiError` não consegue ler — o snackbar aqui seria sempre a mensagem genérica, ao
    // lado da mensagem real que o chamador extrai do blob e mostra inline. Mesmo princípio
    // do ramo acima: quem sabe apresentar o erro é quem fez a chamada.
    if (request.responseType === 'blob') {
      return;
    }

    this.notifications.error(apiError?.message ?? GENERIC_ERROR_MESSAGE);
  }

  /** Limpeza local apenas: o refresh token já está morto, chamar `/auth/logout` seria em vão. */
  private expireSession(): void {
    const returnUrl = this.router.url;
    this.authService.clearSession();
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

  /**
   * Rotas onde um 401 é resposta de negócio, não sessão vencida: nenhuma delas depende
   * de sessão e nenhuma pode ser repetida depois de uma renovação.
   */
  private isAuthEndpoint(url: string): boolean {
    return (
      url.startsWith(`${environment.apiUrl}/auth/login`) ||
      url.startsWith(`${environment.apiUrl}/auth/register`) ||
      url.startsWith(`${environment.apiUrl}/auth/logout`) ||
      url.startsWith(`${environment.apiUrl}/auth/password-reset/`) ||
      url.startsWith(`${environment.apiUrl}/invitations/accept`) ||
      this.isRefreshEndpoint(url)
    );
  }

  private isRefreshEndpoint(url: string): boolean {
    return url.startsWith(`${environment.apiUrl}/auth/refresh`);
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
