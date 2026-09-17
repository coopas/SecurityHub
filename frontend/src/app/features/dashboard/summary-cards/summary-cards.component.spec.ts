import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';

import { SharedModule } from '../../../shared/shared.module';
import { DashboardSummary } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';
import {
  makeDashboardSummary,
  makeEmptyDashboardSummary,
  makeProjectSummary,
} from '../testing/dashboard-test-utils';
import { SummaryCardsComponent } from './summary-cards.component';

describe('SummaryCardsComponent', () => {
  let fixture: ComponentFixture<SummaryCardsComponent>;
  let component: SummaryCardsComponent;
  let dashboardService: jasmine.SpyObj<DashboardService>;

  const setup = (): void => {
    dashboardService = jasmine.createSpyObj<DashboardService>('DashboardService', ['summary']);
    dashboardService.summary.and.returnValue(of(makeDashboardSummary()));

    TestBed.configureTestingModule({
      declarations: [SummaryCardsComponent],
      imports: [SharedModule, RouterTestingModule, NoopAnimationsModule],
      providers: [{ provide: DashboardService, useValue: dashboardService }],
    });

    fixture = TestBed.createComponent(SummaryCardsComponent);
    component = fixture.componentInstance;
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const card = (key: string): HTMLAnchorElement | null =>
    element().querySelector<HTMLAnchorElement>(`[data-testid="summary-card-${key}"]`);

  const cardValue = (key: string): string | undefined =>
    card(key)?.querySelector('.dashboard-card__value')?.textContent?.trim();

  beforeEach(() => setup());

  it('busca o resumo uma vez e mostra cada número em um card', () => {
    fixture.detectChanges();

    expect(dashboardService.summary).toHaveBeenCalledTimes(1);
    expect(element().querySelectorAll('.dashboard-card').length).toBe(7);
    expect(cardValue('total')).toBe('42');
    expect(cardValue('open')).toBe('17');
    expect(cardValue('criticalOpen')).toBe('4');
    expect(cardValue('overdue')).toBe('6');
    expect(cardValue('resolved')).toBe('21');
    expect(cardValue('projects')).toBe('3');
    expect(cardValue('assets')).toBe('11');
  });

  it('leva cada card à listagem filtrada correspondente', () => {
    fixture.detectChanges();

    // Os mesmos parâmetros que `VulnerabilityListComponent.parseQuery` reconhece.
    expect(card('total')?.getAttribute('href')).toBe('/vulnerabilities');
    expect(card('open')?.getAttribute('href')).toBe('/vulnerabilities?status=OPEN');
    expect(card('criticalOpen')?.getAttribute('href')).toBe(
      '/vulnerabilities?severity=CRITICAL&status=OPEN',
    );
    expect(card('overdue')?.getAttribute('href')).toBe('/vulnerabilities?overdue=true');
    expect(card('resolved')?.getAttribute('href')).toBe('/vulnerabilities?status=RESOLVED');
    expect(card('projects')?.getAttribute('href')).toBe('/projects');
    expect(card('assets')?.getAttribute('href')).toBe('/assets');
  });

  it('avisa nos cards cujo destino não reproduz o número exatamente', () => {
    fixture.detectChanges();

    // "Em aberto" é OPEN + IN_PROGRESS no backend e a listagem filtra um status por vez.
    expect(card('open')?.textContent).toContain('A listagem filtra um status por vez');
    expect(card('criticalOpen')?.textContent).toContain('A listagem filtra um status por vez');
    expect(card('overdue')?.textContent).not.toContain('A listagem filtra um status por vez');
  });

  it('lista os projetos com mais achados, cada um linkando para seu filtro', () => {
    dashboardService.summary.and.returnValue(
      of(
        makeDashboardSummary({
          topProjects: [
            makeProjectSummary({ projectId: 3, projectName: 'Portal', total: 9, open: 5, overdue: 2 }),
            makeProjectSummary({ projectId: 7, projectName: 'App', total: 4, open: 1, overdue: 0 }),
          ],
        }),
      ),
    );
    fixture.detectChanges();

    const rows = element().querySelectorAll('[data-testid="top-projects-table"] tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Portal');
    expect(rows[0].querySelectorAll('td')[0].textContent?.trim()).toBe('9');
    expect(rows[0].querySelectorAll('td')[1].textContent?.trim()).toBe('5');
    expect(rows[0].querySelectorAll('td')[2].textContent?.trim()).toBe('2');
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/vulnerabilities?projectId=3');
  });

  it('mostra o estado de carregamento enquanto o resumo não responde', () => {
    const pending = new Subject<DashboardSummary>();
    dashboardService.summary.and.returnValue(pending.asObservable());
    fixture.detectChanges();

    expect(component.state).toBe('loading');
    expect(element().querySelector('[data-testid="summary-state"]')).not.toBeNull();
    expect(element().querySelector('.dashboard-cards')).toBeNull();

    pending.next(makeDashboardSummary());
    pending.complete();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(element().querySelector('.dashboard-cards')).not.toBeNull();
  });

  it('mantém os cards zerados e explica a empresa sem dados', () => {
    dashboardService.summary.and.returnValue(of(makeEmptyDashboardSummary()));
    fixture.detectChanges();

    // Zero é a resposta correta: os cards continuam na tela, com uma frase explicando.
    expect(component.state).toBeNull();
    expect(element().querySelectorAll('.dashboard-card').length).toBe(7);
    expect(cardValue('total')).toBe('0');
    expect(element().querySelector('[data-testid="summary-empty-note"]')).not.toBeNull();
    expect(element().querySelector('[data-testid="top-projects-empty"]')).not.toBeNull();
    expect(element().querySelector('[data-testid="top-projects-table"]')).toBeNull();
  });

  it('mostra erro com nova tentativa e recarrega ao repetir', () => {
    dashboardService.summary.and.returnValue(throwError(() => new Error('falhou')));
    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(element().querySelector('.dashboard-cards')).toBeNull();

    dashboardService.summary.and.returnValue(of(makeDashboardSummary()));
    element().querySelector<HTMLButtonElement>('[data-testid="summary-state"] button')?.click();
    fixture.detectChanges();

    expect(dashboardService.summary).toHaveBeenCalledTimes(2);
    expect(component.state).toBeNull();
    expect(cardValue('total')).toBe('42');
  });
});
