import type { CaptureOperation } from '../../api/captures';
import { knownReasonText } from './captureReasons';

/** Why an operation is not (or not yet) a success, in the words the console shows above the technical code. */
export interface OperationNoticeContent {
  title: string;
  /** The specific explanation: the code's plain text, else the backend's message, else a sentence for the state. */
  detail: string;
  code: string | null;
  color: 'red' | 'amber' | 'gray';
}

const TITLES: Record<string, string> = {
  FAILED: 'This run failed',
  INCOMPLETE: 'This run stopped before finishing',
  WAITING_RETRY: 'Waiting to retry',
  PAUSED: 'Paused',
  CANCELLED: 'Cancelled',
  INTERRUPTED: 'Interrupted by a backend restart',
  BLOCKED: 'This step is blocked',
};

const FALLBACKS: Record<string, string> = {
  FAILED: 'The run failed without a recorded reason. Inspect the saved evidence before retrying.',
  INCOMPLETE:
    'The run ended before its stage finished. Saved evidence is kept; resume to continue.',
  WAITING_RETRY: 'The capture retries on its own at the time shown.',
  PAUSED: 'Paused at its last checkpoint. Resume to continue.',
  CANCELLED: 'The remaining work was cancelled. Saved evidence is kept.',
  INTERRUPTED:
    'The backend restarted during this operation. Inspect the retained evidence, then explicitly resume the selected stage.',
  BLOCKED: 'Inspect the saved evidence to see what blocks this step.',
};

/**
 * The notice for an operation that ended without success or is waiting, or null while it runs or after it succeeded.
 * [stageReason] stands in for the operation's code when it has none, e.g. an incomplete run with no stop reason.
 */
export function operationNotice(
  operation: Pick<CaptureOperation, 'state' | 'code' | 'message'>,
  stageReason?: string | null,
): OperationNoticeContent | null {
  const title = TITLES[operation.state];
  if (!title) return null;
  const code = operation.code ?? stageReason ?? null;
  // A known code reads better than the backend's message, which can be technical (`category=MANIFEST_VERSION`).
  const message = operation.message?.trim() || null;
  return {
    title,
    detail: knownReasonText(code) ?? message ?? FALLBACKS[operation.state] ?? title,
    code,
    color:
      operation.state === 'FAILED' ? 'red' : operation.state === 'CANCELLED' ? 'gray' : 'amber',
  };
}

/** A wait in words: seconds under two minutes, else rounded-up minutes, and hours past an hour. */
export function formatWait(seconds: number): string {
  if (seconds < 120) return `${Math.max(0, Math.ceil(seconds))}s`;
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} h ${rest} min` : `${hours} h`;
}
