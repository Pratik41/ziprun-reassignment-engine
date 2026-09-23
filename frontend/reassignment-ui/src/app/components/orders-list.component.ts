import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';
import { SuggestionCardComponent } from './suggestion-card.component';

@Component({
  selector: 'app-orders-list',
  standalone: true,
  imports: [CommonModule, SuggestionCardComponent],
  template: `
    <div class="orders-container">
      <div class="container-header">
        <div>
          <h2>📋 Orders Pending Reassignment</h2>
          <p class="header-desc">Review and accept/reject AI suggestions</p>
        </div>
        <button (click)="refreshOrders()" class="btn-refresh">
          <span class="refresh-icon">🔄</span> Refresh
        </button>
      </div>

      @if (loading) {
        <div class="loading-state">
          <div class="spinner"></div>
          <p>Loading orders...</p>
        </div>
      } @else if (error) {
        <div class="error-banner">
          <span>⚠️ {{ error }}</span>
          <button (click)="refreshOrders()" class="btn-retry">Retry</button>
        </div>
      } @else if (orders.length === 0) {
        <div class="empty-state">
          <div class="empty-icon">✓</div>
          <h3>No orders pending reassignment</h3>
          <p>All orders are either assigned or delivered</p>
        </div>
      } @else {
        <div class="orders-list">
          @for (order of orders; track order.id) {
            <div class="order-group">
              @if (order.suggestions && order.suggestions.length > 0) {
                @for (suggestion of order.suggestions; track suggestion.id) {
                  <app-suggestion-card
                    [suggestion]="suggestion"
                    [order]="order"
                    (accept)="handleAccept($event)"
                    (reject)="handleReject($event)"
                  ></app-suggestion-card>
                }
              } @else {
                <div class="no-suggestion-card">
                  <div class="no-sugg-content">
                    <div class="no-sugg-text">
                      <h4>Order {{ order.id }}</h4>
                      <p>{{ order.description }}</p>
                      <small>No suggestions available</small>
                    </div>
                    <button (click)="requestSuggestion(order.id)" class="btn-suggest">
                      Get AI Suggestion →
                    </button>
                  </div>
                </div>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .orders-container {
      background: white;
      border-radius: 12px;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
      overflow: hidden;
      display: flex;
      flex-direction: column;
      height: fit-content;
      border: 1px solid #e5e7eb;
    }

    .container-header {
      padding: 24px;
      background: linear-gradient(135deg, #f8f9fc 0%, #f0f4ff 100%);
      border-bottom: 1px solid #e5e7eb;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 20px;
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

    .btn-refresh {
      padding: 10px 18px;
      background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
      color: white;
      border: none;
      border-radius: 8px;
      cursor: pointer;
      font-weight: 600;
      font-size: 0.9rem;
      transition: all 0.3s ease;
      display: flex;
      align-items: center;
      gap: 8px;
      white-space: nowrap;
      box-shadow: 0 2px 8px rgba(37, 99, 235, 0.2);
    }

    .btn-refresh:hover {
      background: linear-gradient(135deg, #1d4ed8 0%, #1e40af 100%);
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(37, 99, 235, 0.3);
    }

    .refresh-icon {
      font-size: 1rem;
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
      display: flex;
      justify-content: space-between;
      align-items: center;
      color: #dc2626;
      font-weight: 500;
    }

    .btn-retry {
      background: #dc2626;
      color: white;
      border: none;
      padding: 6px 14px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 0.85rem;
      font-weight: 600;
      transition: all 0.2s ease;
    }

    .btn-retry:hover {
      background: #b91c1c;
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
      margin: 0 0 8px 0;
      font-size: 1.3rem;
      color: #1f2937;
      font-weight: 600;
    }

    .empty-state p {
      margin: 0;
      color: #6b7280;
      font-size: 0.95rem;
    }

    .orders-list {
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-height: 800px;
      overflow-y: auto;
    }

    .orders-list::-webkit-scrollbar {
      width: 8px;
    }

    .orders-list::-webkit-scrollbar-track {
      background: transparent;
    }

    .orders-list::-webkit-scrollbar-thumb {
      background: #d1d5db;
      border-radius: 4px;
    }

    .orders-list::-webkit-scrollbar-thumb:hover {
      background: #9ca3af;
    }

    .order-group {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .no-suggestion-card {
      background: linear-gradient(135deg, #fef3c7 0%, #fef9e7 100%);
      border: 2px dashed #f59e0b;
      border-radius: 8px;
      padding: 20px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 20px;
    }

    .no-sugg-content {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 20px;
      width: 100%;
    }

    .no-sugg-text {
      text-align: left;
      flex: 1;
    }

    .no-sugg-text h4 {
      margin: 0 0 4px 0;
      font-size: 1rem;
      color: #92400e;
    }

    .no-sugg-text p {
      margin: 0 0 8px 0;
      font-size: 0.9rem;
      color: #b45309;
    }

    .no-sugg-text small {
      color: #a16207;
      font-size: 0.8rem;
    }

    .btn-suggest {
      padding: 10px 18px;
      background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
      color: white;
      border: none;
      border-radius: 6px;
      cursor: pointer;
      font-weight: 600;
      font-size: 0.85rem;
      transition: all 0.3s ease;
      white-space: nowrap;
      box-shadow: 0 2px 8px rgba(245, 158, 11, 0.2);
    }

    .btn-suggest:hover {
      background: linear-gradient(135deg, #d97706 0%, #b45309 100%);
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(245, 158, 11, 0.3);
    }
  `]
})
export class OrdersListComponent implements OnInit {
  orders: any[] = [];
  loading = true;
  error: string | null = null;

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadOrders();
  }

  loadOrders() {
    this.loading = true;
    this.error = null;
    this.apiService.getOrdersByStatus('REASSIGNMENT_PENDING').subscribe({
      next: (orders) => {
        this.orders = orders;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load orders';
        this.loading = false;
      }
    });
  }

  refreshOrders() {
    this.loadOrders();
  }

  requestSuggestion(orderId: string) {
    this.apiService.getSuggestion(orderId).subscribe({
      next: () => {
        this.loadOrders();
      },
      error: () => {
        this.error = 'Failed to get suggestion';
      }
    });
  }

  handleAccept(suggestionId: string) {
    this.apiService.acceptSuggestion(suggestionId).subscribe({
      next: () => {
        this.loadOrders();
      },
      error: () => {
        this.error = 'Failed to accept suggestion';
      }
    });
  }

  handleReject(suggestionId: string) {
    this.apiService.rejectSuggestion(suggestionId).subscribe({
      next: () => {
        this.loadOrders();
      },
      error: () => {
        this.error = 'Failed to reject suggestion';
      }
    });
  }
}
