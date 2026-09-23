import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';
import { RefreshService } from '../services/refresh.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-orders-by-agent',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="agent-orders-container">
      <div class="container-header">
        <h2>📦 Orders by Agent</h2>
        <p class="header-desc">Live breakdown of all orders currently assigned</p>
      </div>

      @if (loading) {
        <div class="loading-state">
          <div class="spinner"></div>
          <p>Loading orders...</p>
        </div>
      } @else if (error) {
        <div class="error-banner">
          <span>⚠️ {{ error }}</span>
        </div>
      } @else if (agentOrders.length === 0) {
        <div class="empty-state">
          <div class="empty-icon">📭</div>
          <h3>No orders assigned</h3>
        </div>
      } @else {
        <div class="agent-orders-list">
          @for (agentOrder of agentOrders; track agentOrder.agent.id) {
            <div class="agent-card" [ngClass]="'status-' + agentOrder.agent.status.toLowerCase()">
              <div class="agent-header">
                <div class="agent-info">
                  <h3>{{ agentOrder.agent.name }}</h3>
                  <span class="agent-id">{{ agentOrder.agent.id }}</span>
                </div>
                <div class="agent-stats">
                  <div class="stat-badge">{{ agentOrder.orders.length }} orders</div>
                  <span class="status-indicator" [ngClass]="'indicator-' + agentOrder.agent.status.toLowerCase()"></span>
                </div>
              </div>

              @if (agentOrder.orders.length > 0) {
                <div class="orders-list">
                  @for (order of agentOrder.orders; track order.id) {
                    <div class="order-item">
                      <div class="order-id-status">
                        <strong>{{ order.id }}</strong>
                        <span class="order-status" [ngClass]="'status-' + order.status.toLowerCase()">
                          {{ order.status }}
                        </span>
                      </div>
                      <p class="order-description">{{ order.description }}</p>
                    </div>
                  }
                </div>
              } @else {
                <div class="no-orders">No orders assigned</div>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .agent-orders-container {
      background: white;
      border-radius: 12px;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
      overflow: hidden;
      display: flex;
      flex-direction: column;
      border: 1px solid #e5e7eb;
    }

    .container-header {
      padding: 24px;
      background: linear-gradient(135deg, #f8f9fc 0%, #f0f4ff 100%);
      border-bottom: 1px solid #e5e7eb;
    }

    .container-header h2 {
      margin: 0;
      font-size: 1.3rem;
      font-weight: 600;
      color: #1f2937;
    }

    .header-desc {
      margin: 6px 0 0 0;
      font-size: 0.9rem;
      color: #6b7280;
      font-weight: 400;
    }

    .loading-state {
      padding: 60px 40px;
      text-align: center;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;
    }

    .spinner {
      width: 40px;
      height: 40px;
      border: 4px solid #e5e7eb;
      border-top-color: #2563eb;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .error-banner {
      background: #fef2f2;
      border-left: 4px solid #ef4444;
      padding: 16px 20px;
      margin: 16px;
      border-radius: 8px;
      color: #dc2626;
      font-weight: 500;
    }

    .empty-state {
      padding: 80px 40px;
      text-align: center;
      background: linear-gradient(135deg, #f9fafb 0%, #f3f4f6 100%);
    }

    .empty-icon {
      font-size: 3rem;
      margin-bottom: 16px;
      display: block;
    }

    .empty-state h3 {
      margin: 0;
      font-size: 1.3rem;
      color: #1f2937;
      font-weight: 600;
    }

    .agent-orders-list {
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-height: 1000px;
      overflow-y: auto;
    }

    .agent-card {
      background: white;
      border: 1px solid #e5e7eb;
      border-radius: 10px;
      padding: 16px;
      border-left: 4px solid #d1d5db;
      transition: all 0.3s ease;
    }

    .agent-card:hover {
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    }

    .agent-card.status-available {
      border-left-color: #10b981;
      background: linear-gradient(135deg, #f0fdf4 0%, white 100%);
    }

    .agent-card.status-busy {
      border-left-color: #f59e0b;
      background: linear-gradient(135deg, #fffbf0 0%, white 100%);
    }

    .agent-card.status-offline {
      border-left-color: #ef4444;
      background: linear-gradient(135deg, #fef2f2 0%, white 100%);
      opacity: 0.85;
    }

    .agent-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 16px;
      padding-bottom: 12px;
      border-bottom: 1px solid #f3f4f6;
    }

    .agent-info h3 {
      margin: 0 0 4px 0;
      font-size: 1rem;
      font-weight: 600;
      color: #1f2937;
    }

    .agent-id {
      font-size: 0.8rem;
      color: #6b7280;
      font-weight: 500;
      background: #f3f4f6;
      padding: 2px 8px;
      border-radius: 4px;
      display: inline-block;
    }

    .agent-stats {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .stat-badge {
      background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
      color: white;
      padding: 6px 12px;
      border-radius: 6px;
      font-size: 0.85rem;
      font-weight: 600;
    }

    .status-indicator {
      display: inline-block;
      width: 10px;
      height: 10px;
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

    .orders-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .order-item {
      background: #f9fafb;
      padding: 12px;
      border-radius: 6px;
      border-left: 3px solid #2563eb;
    }

    .order-id-status {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 4px;
    }

    .order-id-status strong {
      color: #1f2937;
      font-size: 0.95rem;
    }

    .order-status {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 4px;
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.3px;
    }

    .order-status.status-assigned {
      background: #dbeafe;
      color: #1e40af;
    }

    .order-status.status-reassignment_pending {
      background: #fef3c7;
      color: #92400e;
    }

    .order-status.status-reassigned {
      background: #d1fae5;
      color: #065f46;
    }

    .order-status.status-delivered {
      background: #d1d5db;
      color: #374151;
    }

    .order-description {
      margin: 0;
      font-size: 0.9rem;
      color: #6b7280;
      line-height: 1.4;
    }

    .no-orders {
      text-align: center;
      color: #9ca3af;
      font-size: 0.9rem;
      padding: 10px;
      font-style: italic;
    }
  `]
})
export class OrdersByAgentComponent implements OnInit, OnDestroy {
  agentOrders: any[] = [];
  loading = true;
  error: string | null = null;
  private refreshSubscription: Subscription | null = null;

  constructor(private apiService: ApiService, private refreshService: RefreshService) {}

  ngOnInit() {
    this.loadOrdersByAgent();
    this.refreshSubscription = this.refreshService.refresh$.subscribe(() => {
      this.loadOrdersByAgent();
    });
  }

  ngOnDestroy() {
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
    }
  }

  loadOrdersByAgent() {
    this.loading = true;
    this.error = null;

    this.apiService.getAgents().subscribe({
      next: (agents) => {
        this.apiService.getOrders().subscribe({
          next: (orders) => {
            this.agentOrders = agents.map(agent => ({
              agent,
              orders: orders.filter(o => o.assignedAgentId === agent.id)
            }));
            this.loading = false;
          },
          error: () => {
            this.error = 'Failed to load orders';
            this.loading = false;
          }
        });
      },
      error: () => {
        this.error = 'Failed to load agents';
        this.loading = false;
      }
    });
  }
}
