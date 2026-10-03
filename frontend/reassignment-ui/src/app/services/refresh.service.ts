import { Injectable } from '@angular/core';
import { Subject, interval, merge, map } from 'rxjs';

/**
 * Emits whenever views should reload: on demand (after a user action) and
 * every POLL_MS so suggestions created by the async agentic loop appear
 * without anyone clicking refresh.
 */
@Injectable({
  providedIn: 'root'
})
export class RefreshService {
  static readonly POLL_MS = 3000;

  private refreshSubject = new Subject<void>();
  public refresh$ = merge(
    this.refreshSubject,
    interval(RefreshService.POLL_MS).pipe(map(() => undefined))
  );

  triggerRefresh() {
    this.refreshSubject.next();
  }
}
