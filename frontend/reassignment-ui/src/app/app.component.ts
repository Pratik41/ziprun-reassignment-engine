import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AgentRosterComponent } from './components/agent-roster.component';
import { OrdersListComponent } from './components/orders-list.component';
import { DemoPanelComponent } from './components/demo-panel.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, AgentRosterComponent, OrdersListComponent, DemoPanelComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  title = 'ZipRun Reassignment Engine';
}
