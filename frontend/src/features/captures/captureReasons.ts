/**
 * Plain-language guidance for the stage reason codes CaptureInspectionService and CaptureOperationService report,
 * and labels for the wallet-token verdict's reasons and strategy outcomes.
 */
const REASONS: Record<string, string> = {
  WALLET_CAPTURE_NOT_READY: 'Complete and verify the wallet capture first.',
  WALLET_CAPTURE_INCOMPLETE:
    'The wallet capture stopped before finishing. Explicitly resume it with a fresh allowance.',
  NO_SOURCE_BUYS:
    'The wallet capture is complete but holds no supported buy. The rejection and instruction counts below show what it contained, such as sells or buys quoted in a token other than SOL. Start a new session with a different sample.',
  MARKET_CAPTURE_NOT_READY: 'Complete and verify all market windows before capturing rates.',
  MARKET_CAPTURE_INCOMPLETE:
    'Market coverage is incomplete. Increase scan pages if needed and explicitly resume.',
  MARKET_HORIZON_NOT_ELAPSED:
    'A market window has not ended on chain yet, so it has not been listed. Resume after the window end; the capture then finishes it.',
  HORIZON_NOT_ELAPSED:
    'A market window has not ended on chain yet. The capture retries on its own after the estimated end, or resume it then.',
  MARKET_CAPTURE_MISMATCH:
    'The market capture does not match the wallet’s planned windows. Create a new market attempt.',
  MARKET_CAPTURE_EXCLUSIONS:
    'Some in-window trades could not be decoded, so this market attempt cannot be exported.',
  OVER_CAP:
    'Market capacity was exceeded. Create a new market attempt with a larger capacity; resuming cannot fix this attempt.',
  OVER_CAP_AT_MAXIMUM:
    'This sample needs more market transactions than the maximum capacity allows, so no new market attempt can hold it. Start a new session with a smaller sample (for example 5–10 transactions) so fewer tokens need windows.',
  NO_OBSERVATIONS: 'The market windows hold no usable observations.',
  REFERENCE_RATES_INCOMPLETE:
    'Some rate minutes are still missing. Explicitly resume to fetch only those minutes.',
  RESUME_IDENTITY: 'Capture identity does not match. Create a fresh attempt.',
  NOT_CONFIGURED: 'The required provider is not configured in the backend process.',
  PREREQUISITE:
    'The selected capture does not meet this step’s prerequisites. Inspect evidence to refresh readiness.',
  PROCESS_RESTART:
    'The backend restarted during this operation. Inspect the retained evidence, then explicitly resume.',
  QUALIFICATION_PENDING:
    'Dataset exported; qualification has not run yet or did not finish. Use Qualify.',
  EXPORT_CHANGED:
    'The exported dataset changed or is missing since export. Export again, then qualify.',
  EXPORT_REQUIRED: 'Export the dataset before qualifying.',
  UNSUPPORTED_SCOPE: 'Qualification is only available for wallet + token sessions.',
  // Wallet + token scope: the mint, the token-account first buy and the actual first buy.
  MINT_NOT_FOUND:
    'The mint account does not exist. Check the token mint address and start a new session.',
  MINT_PROGRAM_UNSUPPORTED:
    'The mint is not owned by the Token or Token-2022 program, so its token account cannot be derived.',
  TOKEN_PROGRAM_MISMATCH:
    'A captured token balance of this mint reports a different token program than the mint account. The wallet evidence is refused.',
  FIRST_BUY_UNPROVEN:
    'The wallet’s actual first Pump.fun curve buy of this token could not be proven, so market capture cannot start.',
  SAME_SLOT_BUY:
    'Another buy of this token by the wallet is in the same slot as its earliest buy, and the order within a slot is not recorded, so neither can be called the first.',
  FIRST_BUY_NOT_LISTED:
    "The wallet's Pump.fun accumulator listing did not include its known buy, so the provider's listing cannot be trusted to show earlier buys for this case.",
  FIRST_BUY_MISMATCH:
    'The replay started from a different buy than the proven first buy; this is a data problem, not a strategy outcome.',
  NO_TARGET_BUY:
    'The complete token-account history holds no buy of this token, so there is nothing to evaluate.',
  FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT:
    'The wallet’s actual first buy of this token went to another token account. This version does not adopt it, so market capture cannot proceed.',
  HISTORY_NOT_EXHAUSTED:
    "The token-account history was not read to its end: the listing stopped at the sample's record or page limit, so an earlier buy cannot be ruled out. Resuming cannot finish this capture; start a new session with a larger sample or more wallet scan pages.",
  EARLIER_UNDECODED_ACQUISITION:
    'An earlier transaction may have acquired this token through a route the decoder cannot price, such as a multi-hop curve hop or an unsupported buy instruction.',
  EARLIER_UNKNOWN_RECORD:
    'An earlier transaction could not be read (unavailable, unsupported version or withheld), so it cannot be ruled out as a buy.',
  NON_ATA_CREDIT:
    'The earliest decoded buy credited a token account other than the wallet’s associated token account.',
  CREATION_NOT_IDENTIFIED:
    'The token’s Pump.fun creation transaction could not be identified, so there is no lower boundary to verify from.',
  PRE_ACCUMULATOR_MINT:
    'The token was created before Pump.fun required the user volume accumulator (2025-08-01 11:31 UTC), or its creation time is missing, so earlier buys cannot be ruled out.',
  VERIFICATION_BOUND:
    'Verification reached a page or fetch cap before its bound. The scanned counts below show how far it got; this is not a complete result.',
  OTHER_MINT: 'A decoded buy of a different mint appears in the token-account listing.',
  // Strategy outcomes from the qualification replay (dip watch, entry fill and exit).
  NO_DIP: 'No dip: the price never fell far enough below the first buy within the window.',
  DIP_BELOW_MIN_MARKET_CAP: 'The dip happened below the minimum market cap.',
  MIGRATED_BEFORE_ENTRY: 'The token migrated off the bonding curve before an entry.',
  UNSUPPORTED_SUPPLY: 'The token’s supply is not one the strategy supports.',
  NO_FILL_AFTER_TRIGGER: 'No eligible observation after the trigger could fill the entry.',
  EXCEEDS_CURVE: 'The entry would take more tokens than the bonding curve holds.',
  ZERO_QUANTITY: 'The entry would buy zero tokens.',
  INVALID_QUALITY: 'The observation quality is not valid for a fill.',
  HORIZON_REACHED: 'The window ended with the position still open.',
  NO_EXIT_FILL: 'No observation could fill the exit.',
  MIGRATED: 'The token migrated off the bonding curve while the position was open.',
  TAKE_PROFIT: 'Take profit',
  STOP_LOSS: 'Stop loss',
  // Operation stops: budget limits and provider failures.
  REQUESTS: 'Used all the requests allowed for this run. Evidence so far is kept.',
  BYTES: 'Reached the data allowed for this run. Evidence so far is kept.',
  RESPONSE_BYTES: 'A provider answer was larger than this run allows. Evidence so far is kept.',
  DEADLINE: 'Reached the time allowed for this run. Evidence so far is kept.',
  IO: 'The provider connection failed. Evidence so far is kept; it is safe to retry.',
  TIMEOUT: 'The provider did not answer in time. Evidence so far is kept; it is safe to retry.',
  RATE_LIMITED: 'The provider asked us to slow down. Evidence so far is kept; it is safe to retry.',
  UNAVAILABLE: 'The provider was unavailable. Evidence so far is kept; it is safe to retry.',
  HTTP_ERROR: 'The provider returned an error. Evidence so far is kept; it is safe to retry.',
  MARKET_DETAILS_PENDING: 'Some transactions in this token’s market window are not downloaded yet.',
  // Saved-file checks (offline validation of capture folders).
  MANIFEST_VERSION:
    'These saved files were written by an older version in a format this version cannot read. Create a new attempt for this step to capture them again.',
  ARTIFACT_HASH:
    'A saved file no longer matches the checksum recorded when it was captured. Create a new attempt for this step.',
  ARTIFACT_MISSING:
    'A saved file listed in the capture is missing. Create a new attempt for this step.',
  ARTIFACT_SIZE: 'A saved file has an unexpected size. Create a new attempt for this step.',
  ARTIFACT_BYTES: 'A saved file has unexpected contents. Create a new attempt for this step.',
  JSON: 'A saved file could not be read as JSON. Create a new attempt for this step.',
  UNSAFE_PATH: 'A saved file points outside the capture folder, so it is not read.',
  SYMLINK: 'The capture folder contains a link, which is not read for safety.',
  CAPTURE_IN_PROGRESS:
    'These files belong to a capture that has not finished. Resume it or wait until it ends.',
};

export function reasonText(reason: string | null | undefined): string | null {
  if (!reason) return null;
  return REASONS[reason] ?? reason.replaceAll('_', ' ').toLowerCase();
}

/** The plain explanation for a reason code, or null when this version has none (no humanized fallback). */
export function knownReasonText(reason: string | null | undefined): string | null {
  return reason ? (REASONS[reason] ?? null) : null;
}
