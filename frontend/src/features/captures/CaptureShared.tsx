import { Alert, Badge, Button, NumberInput, SimpleGrid, Stack, Text } from '@mantine/core';
import { ApiError } from '../../api/client';
import type { CaptureBudget, CaptureConfig } from '../../api/captures';
import { ErrorState } from '../../components/StateViews';
import { reasonText } from './captureReasons';

const QUALIFICATION_ERRORS = new Set(['EXPORT_CHANGED', 'EXPORT_REQUIRED', 'UNSUPPORTED_SCOPE']);

export function CaptureStatus({ state }: { state: string }) {
  return <Badge variant="light" color={state === 'READY' || state === 'SUCCEEDED' ? 'teal' : state === 'FAILED' || state === 'INVALID' ? 'red' : state === 'NOT_STARTED' ? 'gray' : 'amber'}>{state.replaceAll('_', ' ')}</Badge>;
}
export function CommandError({ error, uncertain, retry }: { error: unknown; uncertain: boolean; retry: () => void }) {
  if (error == null && !uncertain) return null;
  const guidance = error instanceof ApiError && QUALIFICATION_ERRORS.has(error.code) ? reasonText(error.code) : null;
  return <Stack gap="xs">{error != null && <ErrorState error={error} title="Action could not complete" />}{guidance && error instanceof ApiError && guidance !== error.message && <Text size="sm" c="red">{guidance}</Text>}{error instanceof ApiError && Object.keys(error.fields).length > 0 && <Text size="sm" c="red">{Object.entries(error.fields).map(([field, message]) => `${field}: ${message}`).join(' · ')}</Text>}{uncertain && <Alert title="Request outcome is unknown" color="amber"><Stack gap="sm"><Text size="sm">Reconcile the original request before starting another action. Retrying the same request cannot start duplicate work.</Text><Button variant="default" onClick={retry}>Reconcile and retry request</Button></Stack></Alert>}</Stack>;
}
export function BudgetFields({ value, onChange, config, market, disabled }: { value: CaptureBudget; onChange: (value: CaptureBudget) => void; config: CaptureConfig; market: boolean; disabled: boolean }) {
  const prefix = market ? 'market' : 'wallet';
  return <SimpleGrid cols={{ base: 1, sm: 2 }}><NumberInput label="Request allowance" value={value.requestBudget} min={1} max={config.bounds[`${prefix}RequestsMax` as const]} allowDecimal={false} allowNegative={false} disabled={disabled} onChange={v => onChange({...value, requestBudget: Number(v)})} /><NumberInput label="Byte allowance" description="Bytes, per explicit operation" value={value.byteBudget} min={1} max={config.bounds[`${prefix}BytesMax` as const]} allowDecimal={false} allowNegative={false} disabled={disabled} onChange={v => onChange({...value, byteBudget: Number(v)})} /><NumberInput label="Operation deadline (minutes)" description="This run stops at the deadline; resume to continue" value={value.operationTimeoutMinutes} min={1} max={config.bounds.operationTimeoutMinutesMax} allowDecimal={false} allowNegative={false} disabled={disabled} onChange={v => onChange({...value, operationTimeoutMinutes: Number(v)})} /></SimpleGrid>;
}
