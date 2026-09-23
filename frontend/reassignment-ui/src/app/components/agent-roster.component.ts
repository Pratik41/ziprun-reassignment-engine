import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-agent-roster',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="agent-roster">
      <h2>Agent Roster</h2>
      <div class="agents-grid">
        @for (agent of agents; track agent.id) {
          <div class="agent-card" [ngClass]="'status-' + agent.status.toLowerCase()">
            <div class="agent-name">{{ agent.name }}</div>
            <div class="agent-id">{{ agent.id }}</div>
            <div class="agent-status">
              <span class="status-badge" [ngClass]="'badge-' + agent.status.toLowerCase()">
                {{ agent.status }}
              </span>
            </div>
            <div class="agent-load">Orders: {{ agent.activeOrderCount }}</div>
          </div>
        }
      </div>
      @if (loading) {
        <div class="loading">Loading agents...</div>
      }
      @if (error) {
        <div class="error">{{ error }}</div>
      }
    </div>
  `,
  styles: [`
    .agent-roster {
      padding: 20px;
      background: #f5f5f5;
      border-radius: 8px;
      margin-bottom: 20px;
    }

    h2 {
      margin-top: 0;
      color: #333;
    }

    .agents-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 15px;
    }

    .agent-card {
      background: white;
      padding: 15px;
      border-radius: 6px;
      border-left: 4px solid #999;
      box-shadow: 0 2px 4px rgba(0,0,0,0.1);
    }

    .agent-card.status-available {
      border-left-color: #4caf50;
    }

    .agent-card.status-busy {
      border-left-color: #ff9800;
    }

    .agent-card.status-offline {
      border-left-color: #f44336;
      opacity: 0.7;
    }

    .agent-name {
      font-weight: bold;
      color: #333;
      margin-bottom: 5px;
    }

    .agent-id {
      font-size: 12px;
      color: #666;
      margin-bottom: 8px;
    }

    .agent-status {
      margin-bottom: 8px;
    }

    .status-badge {
      display: inline-block;
      padding: 4px 8px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: bold;
      color: white;
    }

    .badge-available {
      background-color: #4caf50;
    }

    .badge-busy {
      background-color: #ff9800;
    }

    .badge-offline {
      background-color: #f44336;
    }

    .agent-load {
      font-size: 12px;
      color: #666;
    }

    .loading, .error {
      padding: 20px;
      text-align: center;
      color: #666;
    }

    .error {
      color: #f44336;
      background: #ffebee;
      border-radius: 4px;
    }
  `]
})
export class AgentRosterComponent implements OnInit {
  agents: any[] = [];
  loading = true;
  error: string | null = null;

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadAgents();
  }

  loadAgents() {
    this.loading = true;
    this.error = null;
    this.apiService.getAgents().subscribe({
      next: (data) => {
        this.agents = data;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load agents';
        this.loading = false;
      }
    });
  }
}
