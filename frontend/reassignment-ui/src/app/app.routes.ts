import { Routes } from '@angular/router';
import { QueuePage } from './pages/queue.page';
import { FleetPage } from './pages/fleet.page';
import { OrdersPage } from './pages/orders.page';
import { InsightsPage } from './pages/insights.page';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'queue' },
  { path: 'queue', component: QueuePage, title: 'Queue · ZipRun Ops' },
  { path: 'fleet', component: FleetPage, title: 'Fleet · ZipRun Ops' },
  { path: 'orders', component: OrdersPage, title: 'Orders · ZipRun Ops' },
  { path: 'insights', component: InsightsPage, title: 'Insights · ZipRun Ops' },
  { path: '**', redirectTo: 'queue' },
];
