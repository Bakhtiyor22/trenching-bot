import type { CaptureOperation, CaptureStageName, CaptureSummary } from '../../api/captures';
import { stageLabels } from '../../lib/codes';

interface NowCopy {
  headline: string;
  body: string;
}

const attentionCopy: Record<CaptureStageName, NowCopy> = {
  WALLET: {
    headline: 'Wallet needs a check',
    body: 'The saved buys could not be verified yet. Re-check saved files, then review the wallet evidence in Advanced before trying again.',
  },
  MARKETS: {
    headline: 'Markets need a check',
    body: 'A market window is incomplete or has a gap. Your downloaded transactions are kept. Re-check saved files and review the affected token below.',
  },
  RATES: {
    headline: 'Rates need a check',
    body: 'Some minute prices are missing or could not be verified. Your wallet and market evidence are kept. Review the rates step in Advanced before trying again.',
  },
  DATASET: {
    headline: 'Dataset needs a check',
    body: 'The offline check could not package a replayable dataset. Your saved evidence is kept. Re-check saved files and review the dataset step in Advanced.',
  },
};

export function nowCopy(summary: CaptureSummary, operation: CaptureOperation | null): NowCopy {
  const stage = stageLabels[summary.currentStep.stage];
  const processed = summary.progress?.processed ?? operation?.progress?.processed ?? 0;
  const selected = summary.progress?.selected ?? operation?.progress?.selected ?? 0;
  const remaining = Math.max(0, selected - processed).toLocaleString('en-US');
  const budget = operation?.budget.requestBudget ?? summary.nextAction.maxRequests;
  const plural = summary.currentStep.stage === 'MARKETS' || summary.currentStep.stage === 'RATES';
  switch (summary.status) {
    case 'STOPPED_LIMIT':
      return {
        headline: /BYTE|DATA_LIMIT/.test(summary.reasonCode ?? operation?.code ?? '')
          ? `${stage} reached this run's data limit`
          : budget != null
            ? `${stage} stopped after using this run's ${budget.toLocaleString('en-US')} requests`
            : `${stage} stopped at this run's limit`,
        body:
          summary.currentStep.stage === 'MARKETS'
            ? `Nothing is lost: ${processed.toLocaleString('en-US')} of ${selected.toLocaleString('en-US')} transactions are saved. Resume to download the remaining ${remaining}. After that come rates, then the dataset.`
            : 'Nothing is lost: this step’s progress is saved. Resume with a fresh allowance to continue where it stopped. The later steps will follow when this one is complete.',
      };
    case 'STOPPED_DEADLINE':
      return {
        headline: `${stage} reached this run's time limit`,
        body: 'The saved progress is kept. Resume to continue from the last checkpoint with a fresh time allowance; you do not need to download the saved transactions again.',
      };
    case 'RUNNING':
      return {
        headline: `${stage} ${plural ? 'are' : 'is'} in progress`,
        body: 'This step is saving its progress as it goes. You can leave this page and return later. The counts below update while the capture is running.',
      };
    case 'PAUSED':
      return {
        headline: `${stage} ${plural ? 'are' : 'is'} paused`,
        body: 'Your saved progress is kept and no new batch will start while paused. Resume when you are ready to continue within the automatic resume limits you set.',
      };
    case 'WAITING_RETRY':
      return {
        headline: `${stage} ${plural ? 'are' : 'is'} waiting to resume automatically`,
        body: 'The provider asked for a pause or this run reached its allowance. Saved progress is kept. Automatic resume will continue within your total request, data and attempt limits.',
      };
    case 'NEEDS_ATTENTION':
      return attentionCopy[summary.currentStep.stage];
    case 'READY_TO_REPLAY':
      return {
        headline: 'Your saved evidence is ready for a dataset',
        body: 'Wallet buys, market trades and reference rates are ready. Prepare the dataset to check and package them for replay. This is an offline step and sends no provider requests.',
      };
    case 'DONE':
      return {
        headline: 'Your dataset is ready to replay',
        body: 'The saved evidence has been checked and packaged. Start a run to test the strategy against these recorded trades, or open the dataset to inspect it.',
      };
    case 'NOT_STARTED':
      return summary.currentStep.stage === 'WALLET'
        ? {
            headline: 'Start with the wallet’s buys',
            body: 'The wallet step finds the buys this capture will follow. Next come each token’s market trades, reference rates and a dataset you can replay.',
          }
        : {
            headline: `${stage} ${plural ? 'are' : 'is'} ready to start`,
            body: 'The preceding step is saved. Continue with the next action below; the remaining steps will become available as their evidence is ready.',
          };
    case 'CANCELLED':
      return {
        headline: `${stage} ${plural ? 'were' : 'was'} cancelled`,
        body: 'The operation has stopped and its saved evidence is kept. Re-check saved files to see what is usable, then choose how to continue in Advanced.',
      };
  }
}
