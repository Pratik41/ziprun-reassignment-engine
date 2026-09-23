import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-suggestion-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="suggestion-card" [ngClass]="suggestion.triggerReason === 'AGENT_OFFLINE' ? 'replan-badge' : ''">
      @if (suggestion.triggerReason === 'AGENT_OFFLINE') {
        <div class="badge-replan">🔄 AUTO RE-PLAN</div>
      }

      <div class="suggestion-content">
        <div class="order-info">
          <strong>Order: {{ order.id }}</strong>
          <p>{{ order.description }}</p>
        </div>

        <div class="suggestion-info">
          <div class="recommended-agent">
            <label>Recommended Agent:</label>
            <strong>{{ suggestion.recommendedAgentId }}</strong>
          </div>

          <div class="confidence">
            <label>Confidence:</label>
            <div class="confidence-bar">
              <div
                class="confidence-fill"
                [style.width.%]="suggestion.confidence * 100"
                [ngClass]="getConfidenceClass(suggestion.confidence)"
              ></div>
            </div>
            <span class="confidence-text">{{ (suggestion.confidence * 100).toFixed(0) }}%</span>
          </div>

          <div class="reasoning">
            <label>AI Reasoning:</label>
            <p class="reasoning-text">{{ suggestion.reasoning }}</p>
          </div>
        </div>

        <div class="actions">
          <button (click)="onAccept()" class="btn btn-accept">✓ Accept</button>
          <button (click)="onReject()" class="btn btn-reject">✗ Reject</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .suggestion-card {
      background: white;
      border: 1px solid #e5e7eb;
      border-radius: 10px;
      padding: 20px;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
      position: relative;
      transition: all 0.3s ease;
      border-left: 4px solid #d1d5db;
    }

    .suggestion-card:hover {
      box-shadow: 0 8px 16px rgba(0, 0, 0, 0.1);
    }

    .suggestion-card.replan-badge {
      border-left: 4px solid #3b82f6;
      background: linear-gradient(135deg, #eff6ff 0%, white 100%);
    }

    .badge-replan {
      position: absolute;
      top: 14px;
      right: 14px;
      background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
      color: white;
      padding: 6px 14px;
      border-radius: 20px;
      font-size: 0.75rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.3px;
      box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .suggestion-content {
      display: flex;
      flex-direction: column;
      gap: 15px;
    }

    .order-info {
      border-bottom: 1px solid #f0f0f0;
      padding-bottom: 15px;
    }

    .order-info strong {
      color: #1976d2;
    }

    .order-info p {
      margin: 5px 0 0 0;
      color: #666;
    }

    .suggestion-info {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .recommended-agent {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .recommended-agent label {
      font-weight: 600;
      color: #333;
    }

    .recommended-agent strong {
      background: #e3f2fd;
      padding: 4px 12px;
      border-radius: 4px;
      color: #1976d2;
    }

    .confidence {
      display: flex;
      gap: 10px;
      align-items: center;
    }

    .confidence label {
      font-weight: 600;
      color: #333;
      min-width: 80px;
    }

    .confidence-bar {
      flex: 1;
      height: 20px;
      background: #e0e0e0;
      border-radius: 10px;
      overflow: hidden;
    }

    .confidence-fill {
      height: 100%;
      transition: width 0.3s ease;
    }

    .confidence-fill.high {
      background: linear-gradient(90deg, #4caf50, #66bb6a);
    }

    .confidence-fill.medium {
      background: linear-gradient(90deg, #ff9800, #ffa726);
    }

    .confidence-fill.low {
      background: linear-gradient(90deg, #f44336, #ef5350);
    }

    .confidence-text {
      font-weight: bold;
      color: #333;
      min-width: 40px;
    }

    .reasoning {
      background: #f9f9f9;
      padding: 12px;
      border-radius: 4px;
      border-left: 3px solid #2196f3;
    }

    .reasoning label {
      display: block;
      font-weight: 600;
      color: #333;
      margin-bottom: 6px;
    }

    .reasoning-text {
      margin: 0;
      color: #555;
      line-height: 1.5;
      font-style: italic;
    }

    .actions {
      display: flex;
      gap: 10px;
      justify-content: flex-end;
      padding-top: 10px;
    }

    .btn {
      padding: 8px 20px;
      border: none;
      border-radius: 4px;
      font-weight: bold;
      cursor: pointer;
      transition: all 0.3s ease;
    }

    .btn-accept {
      background-color: #4caf50;
      color: white;
    }

    .btn-accept:hover {
      background-color: #45a049;
      transform: translateY(-2px);
      box-shadow: 0 4px 8px rgba(76, 175, 80, 0.3);
    }

    .btn-reject {
      background-color: #f44336;
      color: white;
    }

    .btn-reject:hover {
      background-color: #da190b;
      transform: translateY(-2px);
      box-shadow: 0 4px 8px rgba(244, 67, 54, 0.3);
    }
  `]
})
export class SuggestionCardComponent {
  @Input() suggestion: any;
  @Input() order: any;
  @Output() accept = new EventEmitter<string>();
  @Output() reject = new EventEmitter<string>();

  onAccept() {
    this.accept.emit(this.suggestion.id);
  }

  onReject() {
    this.reject.emit(this.suggestion.id);
  }

  getConfidenceClass(confidence: number): string {
    if (confidence >= 0.75) return 'high';
    if (confidence >= 0.5) return 'medium';
    return 'low';
  }
}
