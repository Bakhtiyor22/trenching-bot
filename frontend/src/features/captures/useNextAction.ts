import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import {
  capturesApi,
  type CaptureConfig,
  type CaptureSession,
  type NextActionKind,
} from '../../api/captures';
import { actionLabels } from '../../lib/codes';
import { paths } from '../../paths';
import { captureKeys, useCaptureCommand } from './captureQueries';

export const actionSteps: Record<NextActionKind, 'WALLET' | 'MARKET' | 'RATES' | 'INSPECT' | null> =
  {
    START_WALLET: 'WALLET',
    RESUME_WALLET: 'WALLET',
    RETRY_WALLET: 'WALLET',
    START_MARKETS: 'MARKET',
    RESUME_MARKETS: 'MARKET',
    CAPTURE_RATES: 'RATES',
    INSPECT: 'INSPECT',
    REGISTER_DATASET: null,
    START_RUN: null,
    VIEW_RESULTS: null,
    WAIT: null,
    NONE: null,
  };
interface NextCommand {
  requestId: string;
  kind: NextActionKind;
}
export function useNextAction(session: CaptureSession, config?: CaptureConfig) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const action = session.summary?.nextAction;
  const command = useCaptureCommand<NextCommand, unknown>(
    `${session.id}:next-action`,
    async ({ requestId, kind }) => {
      const latest = await capturesApi.session(session.id);
      const next = latest.summary?.nextAction;
      if (!next || next.kind !== kind) return;
      if (kind === 'START_RUN' || kind === 'VIEW_RESULTS') {
        const datasetId = next.caseId
          ? (await capturesApi.cases(session.id)).items.find((item) => item.caseId === next.caseId)
              ?.datasetId
          : latest.datasetId;
        if (datasetId)
          await navigate(
            kind === 'START_RUN' ? paths.newRunFor(datasetId) : paths.runsFor(datasetId),
          );
        return;
      }
      if (kind === 'REGISTER_DATASET') {
        if (next.caseId) {
          const item = (await capturesApi.cases(session.id)).items.find(
            (item) => item.caseId === next.caseId,
          );
          if (!item?.exportReady)
            return capturesApi.caseExport(session.id, next.caseId, { requestId });
          return capturesApi.caseRegister(session.id, next.caseId);
        }
        if (!latest.datasetReady)
          return capturesApi.operate(session.id, {
            requestId,
            step: 'EXPORT',
            ...(latest.marketAttempt ? { attemptId: latest.marketAttempt.id } : {}),
          });
        return capturesApi.import(session.id);
      }
      if (kind === 'WAIT' || kind === 'NONE') return;
      const operation = latest.operation;
      if (operation?.state === 'PAUSED' && (kind === 'RESUME_WALLET' || kind === 'RESUME_MARKETS'))
        return capturesApi.controlOperation(session.id, operation.id, 'resume', { requestId });
      if (next.caseId && kind === 'CAPTURE_RATES')
        return capturesApi.caseRates(session.id, next.caseId, { requestId });
      const step = actionSteps[kind];
      if (!step) return;
      const baseBudget =
        step === 'WALLET' && latest.wallet?.scope === 'wallet-token'
          ? config?.walletTokenBudgetDefaults
          : config?.budgetDefaults;
      return capturesApi.operate(session.id, {
        requestId,
        step,
        ...(next.attemptId ? { attemptId: next.attemptId } : {}),
        ...(next.maxRequests
          ? {
              budget: {
                requestBudget: next.maxRequests,
                byteBudget: baseBudget?.byteBudget ?? 104857600,
                operationTimeoutMinutes: baseBudget?.operationTimeoutMinutes ?? 10,
              },
            }
          : {}),
      });
    },
    async () => {
      await client.invalidateQueries({ queryKey: captureKeys.all });
      await client.invalidateQueries({ queryKey: ['datasets'] });
    },
  );
  const needsRpc =
    action && (actionSteps[action.kind] === 'WALLET' || actionSteps[action.kind] === 'MARKET');
  const unavailable =
    config && needsRpc && !config.rpcConfigured
      ? 'Configure SOLANA_RPC_URL in the backend process to start this step.'
      : config && action?.kind === 'CAPTURE_RATES' && !config.ratesConfigured
        ? 'Reference-rate capture is not configured.'
        : null;
  return {
    ...command,
    label: action ? actionLabels[action.kind] : 'Open capture',
    unavailable,
    disabled: !action || action.kind === 'WAIT' || action.kind === 'NONE' || !!unavailable,
    run: () => {
      if (action) command.submit({ kind: action.kind });
      else void navigate(paths.capture(session.id));
    },
  };
}
