import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { ProjectDetailComponent } from './project-detail/project-detail.component';
import { ProjectFormComponent } from './project-form/project-form.component';
import { ProjectListComponent } from './project-list/project-list.component';
import { ProjectsRoutingModule } from './projects-routing.module';

@NgModule({
  declarations: [ProjectListComponent, ProjectFormComponent, ProjectDetailComponent],
  imports: [SharedModule, ProjectsRoutingModule],
})
export class ProjectsModule {}
