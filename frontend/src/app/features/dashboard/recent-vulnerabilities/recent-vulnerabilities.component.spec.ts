import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';

import { PageResponse } from '../../../core/models';
import { SharedModule } from '../../../shared/shared.module';
import { Vulnerability } from '../../vulnerabilities/models/vulnerability.model';
import { VulnerabilityService } from '../../vulnerabilities/services/vulnerability.service';
import {
  makeVulnerability,
  makeVulnerabilityPage,
} from '../../vulnerabilities/testing/vulnerability-test-utils';
import { RecentVulnerabilitiesComponent } from './recent-vulnerabilities.component';

describe('RecentVulnerabilitiesComponent', () => {
  let fixture: ComponentFixture<RecentVulnerabilitiesComponent>;
  let component: RecentVulnerabilitiesComponent;
  let vulnerabilityService: jasmine.SpyObj<VulnerabilityService>;

  beforeEach(() => {
    vulnerabilityService = jasmine.createSpyObj<VulnerabilityService>('VulnerabilityService', [
      'list',
    ]);
    vulnerabilityService.list.and.returnValue(
      of(makeVulnerabilityPage([makeVulnerability(), makeVulnerability({ id: 2, title: 'XSS' })])),
    );

    TestBed.configureTestingModule({
      declarations: [RecentVulnerabilitiesComponent],
      imports: [SharedModule, RouterTestingModule, NoopAnimationsModule],
      providers: [{ provide: VulnerabilityService, useValue: vulnerabilityService }],
    });

    fixture = TestBed.createComponent(RecentVulnerabilitiesComponent);
    component = fixture.componentInstance;
  });

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('usa a listagem de vulnerabilidades, primeira página por criação desc', () => {
    fixture.detectChanges();

    // Não existe endpoint de "atividade recente": o painel é a listagem que já existe.
    expect(vulnerabilityService.list).toHaveBeenCalledOnceWith({
      page: 0,
      size: 5,
      sort: 'createdAt,desc',
    });
  });

  it('mostra os itens com título, severidade, status e contexto', () => {
    fixture.detectChanges();

    const items = element().querySelectorAll('.dashboard-recent__item');
    expect(items.length).toBe(2);
    expect(items[0].querySelector('a')?.getAttribute('href')).toBe('/vulnerabilities/1');
    expect(items[0].textContent).toContain('SQL Injection no login');
    // Ícone e texto juntos: severidade e status nunca só pela cor.
    expect(items[0].textContent).toContain('Alta');
    expect(items[0].textContent).toContain('Aberta');
    expect(items[0].textContent).toContain('API de pagamentos');
    expect(items[1].querySelector('a')?.getAttribute('href')).toBe('/vulnerabilities/2');
  });

  it('marca as atrasadas com ícone e texto', () => {
    vulnerabilityService.list.and.returnValue(
      of(makeVulnerabilityPage([makeVulnerability({ overdue: true })])),
    );
    fixture.detectChanges();

    expect(element().querySelector('.dashboard-recent__tag--overdue')?.textContent).toContain(
      'Atrasada',
    );
  });

  it('mostra o estado de carregamento antes da resposta', () => {
    const pending = new Subject<PageResponse<Vulnerability>>();
    vulnerabilityService.list.and.returnValue(pending.asObservable());
    fixture.detectChanges();

    expect(component.state).toBe('loading');
    expect(element().querySelector('[data-testid="recent-list"]')).toBeNull();

    pending.next(makeVulnerabilityPage([makeVulnerability()]));
    pending.complete();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(element().querySelectorAll('.dashboard-recent__item').length).toBe(1);
  });

  it('mostra o vazio quando a empresa não tem vulnerabilidades', () => {
    vulnerabilityService.list.and.returnValue(of(makeVulnerabilityPage([])));
    fixture.detectChanges();

    expect(component.state).toBe('empty');
    expect(element().textContent).toContain('Nenhuma vulnerabilidade cadastrada ainda.');
  });

  it('mostra erro com nova tentativa e recarrega ao repetir', () => {
    vulnerabilityService.list.and.returnValue(throwError(() => new Error('falhou')));
    fixture.detectChanges();

    expect(component.state).toBe('error');

    vulnerabilityService.list.and.returnValue(
      of(makeVulnerabilityPage([makeVulnerability({ id: 9, title: 'RCE' })])),
    );
    element().querySelector<HTMLButtonElement>('[data-testid="recent-state"] button')?.click();
    fixture.detectChanges();

    expect(vulnerabilityService.list).toHaveBeenCalledTimes(2);
    expect(component.state).toBeNull();
    expect(element().textContent).toContain('RCE');
  });
});
