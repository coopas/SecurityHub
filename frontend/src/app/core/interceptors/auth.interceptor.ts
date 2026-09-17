import { HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable, Injector } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from '../services/auth.service';

/**
 * Anexa o bearer token somente às chamadas da própria API; qualquer outra origem
 * (assets, CDNs) segue sem o cabeçalho.
 */
@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  constructor(private readonly injector: Injector) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    if (!this.isApiRequest(request.url)) {
      return next.handle(request);
    }

    // Resolvido sob demanda para evitar dependência cíclica com o HttpClient.
    const token = this.injector.get(AuthService).accessToken;
    if (!token) {
      return next.handle(request);
    }

    return next.handle(
      request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }),
    );
  }

  private isApiRequest(url: string): boolean {
    return url.startsWith(environment.apiUrl);
  }
}
