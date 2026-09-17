import { NgModule } from '@angular/core';
import { NgChartsModule } from 'ng2-charts';

import { SharedModule } from '../../shared/shared.module';
import { DashboardPageComponent } from './dashboard-page/dashboard-page.component';
import { DashboardRoutingModule } from './dashboard-routing.module';
import { DistributionChartComponent } from './distribution-chart/distribution-chart.component';
import { RecentVulnerabilitiesComponent } from './recent-vulnerabilities/recent-vulnerabilities.component';
import { SummaryCardsComponent } from './summary-cards/summary-cards.component';
import { TrendChartComponent } from './trend-chart/trend-chart.component';

/**
 * `NgChartsModule` — and with it `chart.js` — is imported only here. Since the dashboard is
 * the only route that uses charts and comes in through `loadChildren`, the library stays in
 * the lazy chunk and does not weigh on the initial bundle.
 */
@NgModule({
  declarations: [
    DashboardPageComponent,
    SummaryCardsComponent,
    DistributionChartComponent,
    TrendChartComponent,
    RecentVulnerabilitiesComponent,
  ],
  imports: [SharedModule, DashboardRoutingModule, NgChartsModule],
})
export class DashboardModule {}
