import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { SharedModule } from '../../shared/shared.module';
import { DashboardPlaceholderComponent } from './dashboard-placeholder.component';

const routes: Routes = [{ path: '', component: DashboardPlaceholderComponent }];

@NgModule({
  declarations: [DashboardPlaceholderComponent],
  imports: [SharedModule, RouterModule.forChild(routes)],
})
export class DashboardModule {}
