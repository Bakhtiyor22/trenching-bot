import type { ReactNode } from 'react';

/**
 * What a status means, which picks its colour: success and failure (gain/loss), running (info blue,
 * with a dot), attention (orange: stopped, rejected, needs a look), pending (dashed outline), neutral.
 * Amber is never a status: it is the accent.
 */
export type StatusTone = 'success' | 'failure' | 'running' | 'attention' | 'pending' | 'neutral' | 'ready';

/** A status as a glyph or dot plus a word, so it never relies on colour alone. */
export function StatusChip({
  tone,
  glyph,
  children,
  title,
}: {
  tone: StatusTone;
  /** A text glyph such as ✓ ✕ – ◷ ▲; running chips show a dot instead when none is given. */
  glyph?: string;
  children: ReactNode;
  title?: string;
}) {
  return (
    <span className="console-chip" data-tone={tone} title={title}>
      {glyph ? <span aria-hidden>{glyph}</span> : tone === 'running' ? <span className="console-chip-dot" aria-hidden /> : null}
      {children}
    </span>
  );
}
