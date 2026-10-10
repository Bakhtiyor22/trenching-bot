/** Plain-language guidance for the stage reason codes CaptureInspectionService and CaptureOperationService report. */
const REASONS: Record<string, string> = {
  WALLET_CAPTURE_NOT_READY: 'Complete and verify the wallet capture first.',
  WALLET_CAPTURE_INCOMPLETE: 'The wallet capture stopped before finishing. Explicitly resume it with a fresh allowance.',
  NO_SOURCE_BUYS: 'The wallet capture is complete but holds no supported buy. The rejection and instruction counts below show what it contained, such as sells or buys quoted in a token other than SOL. Start a new session with a different sample.',
  MARKET_CAPTURE_NOT_READY: 'Complete and verify all market windows before capturing rates.',
  MARKET_CAPTURE_INCOMPLETE: 'Market coverage is incomplete. Increase scan pages if needed and explicitly resume.',
  MARKET_CAPTURE_MISMATCH: 'The market capture does not match the wallet’s planned windows. Create a new market attempt.',
  MARKET_CAPTURE_EXCLUSIONS: 'Some in-window trades could not be decoded, so this market attempt cannot be exported.',
  OVER_CAP: 'Market capacity was exceeded. Create a new market attempt with a larger capacity; resuming cannot fix this attempt.',
  OVER_CAP_AT_MAXIMUM: 'This sample needs more market transactions than the maximum capacity allows, so no new market attempt can hold it. Start a new session with a smaller sample (for example 5–10 transactions) so fewer tokens need windows.',
  NO_OBSERVATIONS: 'The market windows hold no usable observations.',
  REFERENCE_RATES_INCOMPLETE: 'Some rate minutes are still missing. Explicitly resume to fetch only those minutes.',
  RESUME_IDENTITY: 'Capture identity does not match. Create a fresh attempt.',
  NOT_CONFIGURED: 'The required provider is not configured in the backend process.',
  PREREQUISITE: 'The selected capture does not meet this step’s prerequisites. Inspect evidence to refresh readiness.',
  PROCESS_RESTART: 'The backend restarted during this operation. Inspect the retained evidence, then explicitly resume.',
};

export function reasonText(reason: string | null | undefined): string | null {
  if (!reason) return null;
  return REASONS[reason] ?? reason.replaceAll('_', ' ').toLowerCase();
}
