import { bootstrapApplication } from '@angular/platform-browser';

import { appConfig } from './app/app.config';
import { startApplication } from './app/app.start';
import { ShellComponent } from './app/shell/shell.component';

startApplication(() => bootstrapApplication(ShellComponent, appConfig)).catch((error: unknown) =>
  console.error(error),
);
