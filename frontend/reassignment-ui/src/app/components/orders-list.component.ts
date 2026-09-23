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
      <div class="header">
        <h2>Orders Pending Reassignment</h2>
        <button (click)="refreshOrders()" class="btn-refresh">🔄 Refresh</button>
      </div>

      @if (loading) {
        <div class="loading">Loading orders...</div>
      }

      @if (error) {
        <div class="error">{{ error }}</div>
      }

      @if (orders.length === 0 && !loading) {
        <div class="empty-state">
          <p>✓ No orders pending reassignment</p>
          <small>All orders are either assigned or delivered</small>
        </div>
      }

      @for (order of orders; track order.id) {
        <div class="order-section">
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
            <div class="no-suggestion">
              <p>No suggestions available for order {{ order.id }}</p>
              <button (click)="requestSuggestion(order.id)" class="btn-request">
                Request AI Suggestion
              </button>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .orders-container {
      padding: 20px;
      max-width: 1000px;
      margin: 0 auto;
    }

    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 20px;
      border-bottom: 2px solid #e0e0e0;
      padding-bottom: 15px;
    }

    h2 {
      margin: 0;
      color: #333;
    }

    .btn-refresh {
      padding: 8px 16px;
      background-color: #2196f3;
      color: white;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-weight: bold;
      transition: all 0.3s ease;
    }

    .btn-refresh:hover {
      background-color: #1976d2;
      transform: translateY(-2px);
    }

    .loading {
      text-align: center;
      padding: 40px 20px;
      color: #666;
      font-size: 16px;
    }

    .error {
      background-color: #ffebee;
      color: #c62828;
      padding: 15px;
      border-radius: 4px;
      border-left: 4px solid #c62828;
      margin-bottom: 20px;
    }

    .empty-state {
      text-align: center;
      padding: 60px 20px;
      background: #f5f5f5;
      border-radius: 8px;
      color: #666;
    }

    .empty-state p {
      margin: 0;
      font-size: 18px;
      color: #4caf50;
    }

    .empty-state small {
      display: block;
      margin-top: 10px;
      color: #999;
    }

    .order-section {
      margin-bottom: 20px;
    }

    .no-suggestion {
      background: white;
      border: 1px dashed #ccc;
      border-radius: 8px;
      padding: 20px;
      text-align: center;
      color: #666;
    }

    .no-suggestion p {
      margin: 0 0 15px 0;
    }

    .btn-request {
      padding: 8px 16px;
      background-color: #4caf50;
      color: white;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-weight: bold;
      transition: all 0.3s ease;
    }

    .btn-request:hover {
      background-color: #45a049;
      transform: translateY(-2px);
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
