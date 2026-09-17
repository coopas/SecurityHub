import { Component, OnInit } from '@angular/core';

import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  SEVERITY_ICONS,
  SEVERITY_LABELS,
  Severity,
  VULNERABILITY_STATUS_ICONS,
  VULNERABILITY_STATUS_LABELS,
  Vulnerability,
  VulnerabilityStatus,
} from '../../vulnerabilities/models/vulnerability.model';
import { VulnerabilityService } from '../../vulnerabilities/services/vulnerability.service';
import { RECENT_VULNERABILITIES_SIZE, RECENT_VULNERABILITIES_SORT } from '../models/dashboard.model';

/**
 * The dashboard's "Itens recentes".
 *
 * There is no recent-activity endpoint on the backend, and that is deliberate: this panel is
 * the first page of `GET /vulnerabilities` sorted by creation, an endpoint that is already
 * paginated, isolated per company and tested. That is why the vulnerabilities'
 * `VulnerabilityService` is used here instead of a new service.
 */
@Component({
  selector: 'app-recent-vulnerabilities',
  templateUrl: './recent-vulnerabilities.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class RecentVulnerabilitiesComponent implements OnInit {
  readonly size = RECENT_VULNERABILITIES_SIZE;

  state: ViewState | null = 'loading';
  vulnerabilities: Vulnerability[] = [];

  constructor(private readonly vulnerabilityService: VulnerabilityService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'loading';
    this.vulnerabilityService
      .list({ page: 0, size: RECENT_VULNERABILITIES_SIZE, sort: RECENT_VULNERABILITIES_SORT })
      .subscribe({
        next: (page) => {
          this.vulnerabilities = page.content;
          this.state = page.content.length === 0 ? 'empty' : null;
        },
        error: () => {
          this.vulnerabilities = [];
          this.state = 'error';
        },
      });
  }

  severityLabel(severity: Severity): string {
    return SEVERITY_LABELS[severity];
  }

  severityIcon(severity: Severity): string {
    return SEVERITY_ICONS[severity];
  }

  /** Color is reinforcement: the icon and the text already say the severity. */
  severityClass(severity: Severity): string {
    return `dashboard-severity--${severity.toLowerCase()}`;
  }

  statusLabel(status: VulnerabilityStatus): string {
    return VULNERABILITY_STATUS_LABELS[status];
  }

  statusIcon(status: VulnerabilityStatus): string {
    return VULNERABILITY_STATUS_ICONS[status];
  }

  statusClass(status: VulnerabilityStatus): string {
    return `dashboard-status--${status.toLowerCase().replace('_', '-')}`;
  }

  trackById(_index: number, vulnerability: Vulnerability): number {
    return vulnerability.id;
  }
}
