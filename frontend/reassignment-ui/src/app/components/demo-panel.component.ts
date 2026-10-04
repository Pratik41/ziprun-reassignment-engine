import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { RefreshService } from '../services/refresh.service';
import { AgentFilterPipe } from '../pipes/agent-filter.pipe';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-demo-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, AgentFilterPipe],
  template: `
    <div class="demo-panel">
      <h3>🎮 Demo Controls</h3>

      <div class="control-section">
        <h4>⚙️ Routing Strategy <small class="hint">(switches at runtime, no restart)</small></h4>
        <div class="status-buttons">
          @for (name of availableStrategies; track name) {
            <button
              (click)="setStrategy(name)"
              [class.active]="activeStrategy === name"
              class="btn-sm btn-strategy"
            >
              {{ name }}
            </button>
          }
        </div>
      </div>

      <div class="control-section">
        <h4>1️⃣ Create Order</h4>
        <div class="form-group">
          <input
            [(ngModel)]="orderDesc"
            placeholder="Order description"
            type="text"
            class="input-field"
          />
          <select [(ngModel)]="selectedAgent" class="input-field">
            <option value="">Select Agent</option>
            @for (agent of agents; track agent.id) {
              <option [value]="agent.id">{{ agent.name }} ({{ agent.status }})</option>
            }
          </select>
          <button (click)="createOrder()" class="btn-primary">Create Order</button>
        </div>
        @if (orderCreated) {
          <div class="success-msg">✓ Order created: {{ createdOrderId }}</div>
        }
      </div>

      <div class="control-section">
        <h4>2️⃣ Agent Status Control <small class="hint">(at least one agent must stay Available)</small></h4>
        <div class="agents-control">
          @for (agent of agents; track agent.id) {
            <div class="agent-control">
              <span class="agent-label">
                {{ agent.name }}
                @if (isLastAvailable(agent)) {
                  <small class="last-available">only Available agent</small>
                }
              </span>
              <div class="status-buttons">
                <button
                  (click)="setAgentStatus(agent.id, 'AVAILABLE')"
                  [class.active]="agent.status === 'AVAILABLE'"
                  class="btn-sm btn-available"
                >
                  🟢 Available
                </button>
                <button
                  (click)="setAgentStatus(agent.id, 'BUSY')"
                  [class.active]="agent.status === 'BUSY'"
                  [disabled]="isLastAvailable(agent)"
                  [title]="isLastAvailable(agent) ? lastAvailableHint : ''"
                  class="btn-sm btn-busy"
                >
                  🟠 Busy
                </button>
                <button
                  (click)="setAgentStatus(agent.id, 'OFFLINE')"
                  [class.active]="agent.status === 'OFFLINE'"
                  [disabled]="isLastAvailable(agent)"
                  [title]="isLastAvailable(agent) ? lastAvailableHint : ''"
                  class="btn-sm btn-offline"
                >
                  🔴 Offline
                </button>
              </div>
            </div>
          }
        </div>
        @if (agentStatusChanged) {
          <div class="success-msg">✓ Agent status updated (agentic loop triggered!)</div>
        }
      </div>

      <div class="control-section">
        <h4>📊 Current State</h4>
        <div class="state-info">
          <p><strong>Total Agents:</strong> {{ agents.length }}</p>
          <p><strong>Available:</strong> {{ (agents | agentFilter:'AVAILABLE').length }}</p>
          <p><strong>Busy:</strong> {{ (agents | agentFilter:'BUSY').length }}</p>
          <p><strong>Offline:</strong> {{ (agents | agentFilter:'OFFLINE').length }}</p>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .demo-panel {
      background: white;
      border: 2px solid #3b82f6;
      border-radius: 10px;
      padding: 20px;
      margin-bottom: 20px;
      box-shadow: 0 4px 12px rgba(59, 130, 246, 0.15);
    }

    h3 {
      margin: 0 0 16px 0;
      color: #1f2937;
      font-size: 1.1rem;
    }

    h4 {
      margin: 0 0 12px 0;
      color: #374151;
      font-size: 0.95rem;
      font-weight: 600;
    }

    .control-section {
      margin-bottom: 20px;
      padding-bottom: 16px;
      border-bottom: 1px solid #e5e7eb;
    }

    .control-section:last-child {
      border-bottom: none;
      margin-bottom: 0;
      padding-bottom: 0;
    }

    .form-group {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }

    .input-field {
      flex: 1;
      min-width: 150px;
      padding: 10px 12px;
      border: 1px solid #d1d5db;
      border-radius: 6px;
      font-size: 0.9rem;
      font-family: inherit;
    }

    .input-field:focus {
      outline: none;
      border-color: #3b82f6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
    }

    .btn-primary, .btn-secondary {
      padding: 10px 16px;
      border: none;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
      font-size: 0.9rem;
      transition: all 0.2s ease;
      white-space: nowrap;
    }

    .btn-primary {
      background: #3b82f6;
      color: white;
    }

    .btn-primary:hover {
      background: #2563eb;
      transform: translateY(-1px);
    }

    .btn-secondary {
      background: #f3f4f6;
      color: #1f2937;
      border: 1px solid #d1d5db;
    }

    .btn-secondary:hover {
      background: #e5e7eb;
    }

    .agents-control {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .agent-control {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px;
      background: #f9fafb;
      border-radius: 6px;
      border: 1px solid #e5e7eb;
    }

    .agent-label {
      font-weight: 600;
      color: #1f2937;
      min-width: 120px;
    }

    .status-buttons {
      display: flex;
      gap: 8px;
    }

    .btn-sm {
      padding: 6px 12px;
      border: 1px solid #d1d5db;
      border-radius: 4px;
      font-size: 0.8rem;
      font-weight: 600;
      cursor: pointer;
      background: white;
      color: #6b7280;
      transition: all 0.2s ease;
    }

    .hint {
      font-weight: 400;
      color: #6b7280;
    }

    .last-available {
      display: block;
      font-weight: 500;
      font-size: 0.72rem;
      color: #047857;
    }

    .btn-sm:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .btn-strategy {
      color: #6366f1;
      border-color: #6366f1;
    }

    .btn-strategy.active {
      background: #6366f1;
      color: white;
    }

    .btn-available {
      color: #10b981;
      border-color: #10b981;
    }

    .btn-available.active {
      background: #10b981;
      color: white;
    }

    .btn-busy {
      color: #f59e0b;
      border-color: #f59e0b;
    }

    .btn-busy.active {
      background: #f59e0b;
      color: white;
    }

    .btn-offline {
      color: #ef4444;
      border-color: #ef4444;
    }

    .btn-offline.active {
      background: #ef4444;
      color: white;
    }

    .success-msg, .info-msg {
      margin-top: 10px;
      padding: 10px 12px;
      border-radius: 6px;
      font-size: 0.9rem;
      font-weight: 500;
    }

    .success-msg {
      background: #d1fae5;
      color: #065f46;
      border-left: 3px solid #10b981;
    }

    .info-msg {
      background: #dbeafe;
      color: #1e40af;
      border-left: 3px solid #3b82f6;
    }

    .state-info {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 12px;
      background: #f9fafb;
      padding: 12px;
      border-radius: 6px;
    }

    .state-info p {
      margin: 0;
      font-size: 0.9rem;
      color: #374151;
    }

    .state-info strong {
      color: #1f2937;
    }
  `]
})
export class DemoPanelComponent implements OnInit, OnDestroy {
  agents: any[] = [];
  orderDesc = '';
  selectedAgent = '';
  orderCreated = false;
  createdOrderId = '';
  agentStatusChanged = false;
  readonly lastAvailableHint = 'This is the only Available agent. Make another agent Available first.';
  activeStrategy = '';
  availableStrategies: string[] = [];
  private refreshSubscription: Subscription | null = null;

  constructor(private apiService: ApiService, private refreshService: RefreshService) {}

  ngOnInit() {
    this.loadAgents();
    this.loadStrategy();
    this.refreshSubscription = this.refreshService.refresh$.subscribe(() => this.loadAgents());
  }

  loadStrategy() {
    this.apiService.getRoutingStrategy().subscribe({
      next: (s) => {
        this.activeStrategy = s.active;
        this.availableStrategies = s.available;
      },
      error: (err) => console.error('Failed to load routing strategy', err)
    });
  }

  setStrategy(name: string) {
    this.apiService.setRoutingStrategy(name).subscribe({
      next: (s) => this.activeStrategy = s.active,
      error: (err) => alert('Failed to switch strategy: ' + (err.error?.message ?? 'unknown error'))
    });
  }

  loadAgents() {
    this.apiService.getAgents().subscribe({
      next: (data) => {
        this.agents = data;
      },
      error: (err) => {
        console.error('Failed to load agents', err);
      }
    });
  }

  createOrder() {
    if (!this.orderDesc || !this.selectedAgent) {
      alert('Please enter description and select agent');
      return;
    }

    this.apiService.createOrder({
      description: this.orderDesc,
      assignedAgentId: this.selectedAgent
    }).subscribe({
      next: (order) => {
        this.createdOrderId = order.id;
        this.orderCreated = true;
        this.orderDesc = '';
        setTimeout(() => this.orderCreated = false, 3000);
        this.refreshNow();
      },
      error: (err) => {
        alert('Failed to create order: ' + err.error?.message);
      }
    });
  }

  /** Mirrors the backend rule: the last Available agent can't go Busy/Offline. */
  isLastAvailable(agent: any): boolean {
    return agent.status === 'AVAILABLE'
      && this.agents.filter(a => a.status === 'AVAILABLE').length === 1;
  }

  setAgentStatus(agentId: string, status: string) {
    this.apiService.updateAgentStatus(agentId, status).subscribe({
      next: () => {
        this.loadAgents();
        this.agentStatusChanged = true;
        setTimeout(() => this.agentStatusChanged = false, 3000);
        this.refreshNow();
      },
      error: (err) => {
        alert('Failed to update agent status: ' + (err.error?.message ?? 'unknown error'));
      }
    });
  }

  refreshNow() {
    this.loadAgents();
    this.refreshService.triggerRefresh();
  }

  ngOnDestroy() {
    this.refreshSubscription?.unsubscribe();
  }
}
