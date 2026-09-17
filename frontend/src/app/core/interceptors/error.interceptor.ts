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
 * Central HTTP error handling and the only place that refreshes the session.
 *
 * A 401 on an authenticated route fires a refresh and repeats the original request; only
 * when the refresh fails does the session go down. It always rethrows the error so that the
 * screen can react (for example, showing `fieldErrors` on the form fields).
 */
@Injectable()
export class ErrorInterceptor implements HttpInterceptor {
  /**
   * In-flight refresh, shared by every 401 of the same burst. `refCount: false` is
   * deliberate: with `true`, the first request to give up would cancel the refresh POST and
   * the rest would sit waiting for a response nobody had asked for any more.
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
   * Refreshes once and repeats the request.
   *
   * The retry happens in here, after the outer `catchError` has already fired: an error
   * from the second attempt lands in the inner handling and does not enter `intercept`
   * again, so there is no loop of 401s refreshing forever.
   */
  private refreshAndRetry(
    request: HttpRequest<unknown>,
    next: HttpHandler,
    original: HttpErrorResponse,
  ): Observable<HttpEvent<unknown>> {
    return this.sharedRefresh().pipe(
      catchError(() => {
        // The refresh was the session's last chance; the refresh token is dead too.
        this.expireSession();
        return throwError(() => original);
      }),
      switchMap((response) =>
        // The `AuthInterceptor` runs before this one and will not see the repeated
        // request: without this clone it would carry back the expired access token.
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
   * One refresh per burst. The `finalize` clears the reference when it ends, so that the
   * next 401 starts a fresh refresh instead of reviving the old result.
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
   * Refreshing is only worth it when there is something to refresh and the request is to
   * our own API — attaching a bearer token to a foreign origin would be leaking it. With no
   * refresh token stored, the 401 follows the usual path: end the session.
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
      // The 401 of the refresh itself is decided by `refreshAndRetry`, which already ends
      // the session and warns once. Warning here too would show two snackbars for one event.
      if (this.isRefreshEndpoint(request.url)) {
        return;
      }
      // A credential failure on the authentication screen itself brings down no session.
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

    // Validation errors are displayed inline by the forms.
    if (apiError?.code === 'VALIDATION_ERROR') {
      return;
    }

    // On a `responseType: 'blob'` request the error body also arrives as a Blob, which
    // `asApiError` cannot read — the snackbar here would always be the generic message, next
    // to the real message the caller extracts from the blob and shows inline. Same principle
    // as the branch above: whoever made the call is who knows how to present the error.
    if (request.responseType === 'blob') {
      return;
    }

    this.notifications.error(apiError?.message ?? GENERIC_ERROR_MESSAGE);
  }

  /** Local cleanup only: the refresh token is dead, calling `/auth/logout` would be in vain. */
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
   * Routes where a 401 is a business answer, not an expired session: none of them depends
   * on a session and none can be repeated after a refresh.
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

  // Resolved lazily to avoid a cyclic dependency with the HttpClient.
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
