import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { VulnerabilitiesRoutingModule } from './vulnerabilities-routing.module';
import { VulnerabilityDetailComponent } from './vulnerability-detail/vulnerability-detail.component';
import { VulnerabilityFormComponent } from './vulnerability-form/vulnerability-form.component';
import { VulnerabilityListComponent } from './vulnerability-list/vulnerability-list.component';

@NgModule({
  declarations: [
    VulnerabilityListComponent,
    VulnerabilityFormComponent,
    VulnerabilityDetailComponent,
  ],
  imports: [SharedModule, VulnerabilitiesRoutingModule],
})
export class VulnerabilitiesModule {}
