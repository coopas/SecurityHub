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
 * `NgChartsModule` — e com ele o `chart.js` — é importado apenas aqui. Como o dashboard é
 * a única rota que usa gráficos e entra por `loadChildren`, a biblioteca fica no pedaço
 * lazy e não pesa no bundle inicial.
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
