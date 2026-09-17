import { Component, EventEmitter, Input, Output } from '@angular/core';

export type ViewState = 'loading' | 'empty' | 'error';

/**
 * Async states (loading, empty and error) required on every screen.
 * Announced by screen readers via `role="status"` + `aria-live="polite"`.
 */
@Component({
  selector: 'app-state-message',
  templateUrl: './state-message.component.html',
  styleUrls: ['./state-message.component.scss'],
})
export class StateMessageComponent {
  @Input({ required: true }) state: ViewState = 'loading';
  @Input() message?: string;
  @Input() retryLabel = 'Tentar novamente';
  @Input() showRetry = false;

  @Output() readonly retry = new EventEmitter<void>();

  get text(): string {
    if (this.message) {
      return this.message;
    }
    switch (this.state) {
      case 'loading':
        return 'Carregando...';
      case 'empty':
        return 'Nenhum registro encontrado.';
      default:
        return 'Não foi possível carregar os dados.';
    }
  }

  get icon(): string {
    return this.state === 'empty' ? 'inbox' : 'error_outline';
  }
}
