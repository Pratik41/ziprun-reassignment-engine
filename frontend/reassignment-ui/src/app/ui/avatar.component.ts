import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { AgentStatus } from '../models';
import { avatarHue, initials } from '../labels';

/** Agent initials in a stable colour, with an optional presence dot. */
@Component({
  selector: 'app-avatar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="avatar" [class.avatar-sm]="size === 'sm'" [class.avatar-lg]="size === 'lg'"
          [style.--hue]="hue" [attr.title]="name">
      {{ letters }}
      @if (status) {
        <span class="presence" [class]="'presence ' + status"></span>
      }
    </span>
  `,
  styles: [`:host { display: inline-flex; }`],
})
export class AvatarComponent {
  letters = '';
  hue = 230;

  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() status: AgentStatus | null = null;
  @Input() name = '';

  @Input({ required: true }) set agentId(id: string) {
    this.hue = avatarHue(id);
  }

  @Input({ required: true }) set agentName(name: string) {
    this.name = name;
    this.letters = initials(name);
  }
}
