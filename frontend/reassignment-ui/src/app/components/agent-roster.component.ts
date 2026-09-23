import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';
import { RefreshService } from '../services/refresh.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-agent-roster',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="agent-roster">
      <div class="roster-header">
        <h3>👥 Agent Roster</h3>
        <button class="btn-mini" (click)="loadAgents()" title="Refresh">🔄</button>
      </div>

      @if (loading) {
        <div class="loading-state">
          <div class="spinner"></div>
          <p>Loading agents...</p>
        </div>
      } @else if (error) {
        <div class="error-state">
          <p>⚠️ {{ error }}</p>
        </div>
      } @else {
        <div class="agents-list">
          @for (agent of agents; track agent.id) {
            <div class="agent-item" [ngClass]="'status-' + agent.status.toLowerCase()">
              <div class="agent-header">
                <div class="agent-name">{{ agent.name }}</div>
                <span class="status-indicator" [ngClass]="'indicator-' + agent.status.toLowerCase()"></span>
              </div>
              <div class="agent-meta">
                <span class="agent-id">{{ agent.id }}</span>
              </div>
              <div class="agent-stats">
                <div class="stat-item">
                  <span class="stat-icon">📦</span>
                  <span class="stat-text">{{ agent.activeOrderCount }} orders</span>
                </div>
              </div>
              <div class="agent-footer">
                <span class="status-badge" [ngClass]="'badge-' + agent.status.toLowerCase()">
                  {{ agent.status }}
                </span>
              </div>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .agent-roster {
      background: white;
      border-radius: 12px;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
      overflow: hidden;
      display: flex;
      flex-direction: column;
      height: 100%;
      border: 1px solid #e5e7eb;
    }

    .roster-header {
      padding: 20px;
      background: linear-gradient(135deg, #f8f9fc 0%, #f0f4ff 100%);
      border-bottom: 1px solid #e5e7eb;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .roster-header h3 {
      margin: 0;
      font-size: 1.1rem;
      font-weight: 600;
      color: #1f2937;
    }

    .btn-mini {
      background: none;
      border: none;
      font-size: 1.2rem;
      cursor: pointer;
      padding: 4px 8px;
      border-radius: 4px;
      transition: all 0.2s ease;
    }

    .btn-mini:hover {
      background: #e5e7eb;
      transform: rotate(180deg);
    }

    .agents-list {
      flex: 1;
      overflow-y: auto;
      padding: 12px;
    }

    .agent-item {
      background: white;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 14px;
      margin-bottom: 10px;
      transition: all 0.3s ease;
      border-left: 3px solid #d1d5db;
      position: relative;
    }

    .agent-item:hover {
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
      transform: translateX(4px);
    }

    .agent-item.status-available {
      border-left-color: #10b981;
      background: linear-gradient(135deg, #f0fdf4 0%, white 100%);
    }

    .agent-item.status-busy {
      border-left-color: #f59e0b;
      background: linear-gradient(135deg, #fffbf0 0%, white 100%);
    }

    .agent-item.status-offline {
      border-left-color: #ef4444;
      background: linear-gradient(135deg, #fef2f2 0%, white 100%);
      opacity: 0.85;
    }

    .agent-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 10px;
    }

    .agent-name {
      font-weight: 600;
      color: #1f2937;
      font-size: 0.95rem;
    }

    .status-indicator {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      animation: pulse 2s infinite;
    }

    .indicator-available {
      background-color: #10b981;
    }

    .indicator-busy {
      background-color: #f59e0b;
    }

    .indicator-offline {
      background-color: #ef4444;
      animation: none;
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.6; }
    }

    .agent-meta {
      margin-bottom: 10px;
    }

    .agent-id {
      font-size: 0.8rem;
      color: #6b7280;
      font-weight: 500;
      background: #f3f4f6;
      padding: 3px 8px;
      border-radius: 4px;
      display: inline-block;
    }

    .agent-stats {
      margin-bottom: 10px;
    }

    .stat-item {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 0.85rem;
      color: #6b7280;
    }

    .stat-icon {
      font-size: 1rem;
    }

    .stat-text {
      font-weight: 500;
    }

    .agent-footer {
      border-top: 1px solid #e5e7eb;
      padding-top: 10px;
    }

    .status-badge {
      display: inline-block;
      padding: 5px 10px;
      border-radius: 6px;
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.3px;
      color: white;
    }

    .badge-available {
      background-color: #10b981;
    }

    .badge-busy {
      background-color: #f59e0b;
    }

    .badge-offline {
      background-color: #ef4444;
    }

    .loading-state, .error-state {
      padding: 40px 20px;
      text-align: center;
      color: #6b7280;
    }

    .loading-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;
    }

    .spinner {
      width: 32px;
      height: 32px;
      border: 3px solid #e5e7eb;
      border-top-color: #2563eb;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .error-state {
      background: #fef2f2;
      border: 1px solid #fee2e2;
      border-radius: 6px;
      color: #dc2626;
    }

    .error-state p {
      margin: 0;
      font-weight: 500;
    }

    /* Scrollbar styling */
    .agents-list::-webkit-scrollbar {
      width: 6px;
    }

    .agents-list::-webkit-scrollbar-track {
      background: transparent;
    }

    .agents-list::-webkit-scrollbar-thumb {
      background: #d1d5db;
      border-radius: 3px;
    }

    .agents-list::-webkit-scrollbar-thumb:hover {
      background: #9ca3af;
    }
  `]
})
export class AgentRosterComponent implements OnInit, OnDestroy {
  agents: any[] = [];
  loading = true;
  error: string | null = null;
  private refreshSubscription: Subscription | null = null;

  constructor(private apiService: ApiService, private refreshService: RefreshService) {}

  ngOnInit() {
    this.loadAgents();
    // Listen for refresh events
    this.refreshSubscription = this.refreshService.refresh$.subscribe(() => {
      this.loadAgents();
    });
  }

  ngOnDestroy() {
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
    }
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
