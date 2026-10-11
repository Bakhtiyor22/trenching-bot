import { Anchor, Badge, Button, Group, Stack, Table, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import type { ActualFirstBuy, TargetToken, TokenAccountFirstBuy, VerdictClassification, Verdict, VerificationBound, VerificationListing } from '../../api/captures';
import { ShortId, Timestamp } from '../../components/DisplayValues';
import { Section } from '../../components/Panels';
import { reasonText } from './captureReasons';
import styles from './TokenVerdict.module.css';

/** Distinct wording per classification. Only NEGATIVE_CASE is a negative strategy outcome; capture and replay problems never read as one. */
const CLASSIFICATIONS: Record<VerdictClassification, { label: string; color: string; detail: string }> = {
  QUALIFYING_DIP: { label: 'Qualifying dip', color: 'teal', detail: 'The dip condition was met after the proven first buy.' },
  NEGATIVE_CASE: { label: 'Negative case', color: 'gray', detail: 'The capture is complete and the dip condition was not met.' },
  INCOMPLETE_CAPTURE: { label: 'Capture incomplete', color: 'amber', detail: 'The strategy has not been evaluated; complete the capture first. This is not a strategy outcome.' },
  FIRST_BUY_UNPROVEN: { label: 'First buy unproven', color: 'amber', detail: 'The wallet’s actual first buy could not be proven, so the strategy cannot be evaluated. This is not a strategy outcome.' },
  FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT: { label: 'First buy outside token account', color: 'red', detail: 'The wallet’s actual first buy went to another token account, so the strategy cannot be evaluated. This is not a strategy outcome.' },
  NO_TARGET_BUY: { label: 'No buy of this token', color: 'gray', detail: 'The complete token-account history holds no buy of this token. This is not a strategy outcome.' },
  REPLAY_FAILED: { label: 'Replay failed', color: 'red', detail: 'The replay could not run to a result. This is not a strategy outcome.' },
};
const STATE_COLORS: Record<string, string> = {
  FOUND: 'teal', PROVEN: 'teal', COMPLETE: 'teal', QUALIFIED: 'teal', FILLED: 'teal', CLOSED: 'teal', RAN: 'teal',
  OUTSIDE_TOKEN_ACCOUNT: 'red', UNUSABLE: 'red', FAILED: 'red',
  NOT_ESTABLISHED: 'amber', UNPROVEN: 'amber', INCOMPLETE: 'amber', NOT_FILLED: 'amber', OPEN: 'amber',
};
const BOUND_LIMITS: Record<VerificationBound['limit'], string> = {
  MINT_PAGES: 'Mint listing page cap',
  PUMP_ACCUMULATOR_PAGES: 'Pump accumulator page cap',
  PUMPSWAP_ACCUMULATOR_PAGES: 'PumpSwap accumulator page cap',
  FETCHES: 'Transaction fetch cap',
};
const LISTINGS: Record<VerificationListing, string> = { MINT: 'Mint', PUMP_ACCUMULATOR: 'Pump accumulator', PUMPSWAP_ACCUMULATOR: 'PumpSwap accumulator' };
const OUTSIDE_SENTENCE = 'The wallet’s actual first buy went to another token account; this version does not adopt it, so market capture cannot proceed.';
const WRAPPING_STYLE = { overflowWrap: 'anywhere' } as const;
const slotText = (slot: number | null | undefined) => slot == null ? '—' : String(slot);

function VerdictState({state}: {state: string}) {
  return <Badge variant="light" color={STATE_COLORS[state] ?? 'gray'}>{state.replaceAll('_', ' ')}</Badge>;
}
function Tx({signature}: {signature: string}) {
  return <Anchor href={`https://solscan.io/tx/${encodeURIComponent(signature)}`} target="_blank" rel="noopener noreferrer" style={WRAPPING_STYLE}><ShortId value={signature} style={WRAPPING_STYLE}/></Anchor>;
}
/** A signature with its slot, e.g. for the buy a result points at. */
function TxAt({label, signature, slot}: {label: string; signature?: string | null; slot?: number | null}) {
  if (!signature) return null;
  return <Group gap="xs" wrap="wrap"><Text size="sm" c="dimmed">{label}</Text><Tx signature={signature}/><Text size="sm" ff="monospace">slot {slotText(slot)}</Text></Group>;
}
function Blocking({signatures}: {signatures: string[]}) {
  if (!signatures.length) return null;
  return <Group gap="xs" wrap="wrap"><Text size="sm" c="dimmed">Blocking</Text>{signatures.map(signature=><Tx key={signature} signature={signature}/>)}</Group>;
}
function Exact({label, value}: {label: string; value: ReactNode}) {
  return <Text size="sm"><Text span c="dimmed">{label} </Text><Text span ff="monospace">{value ?? '—'}</Text></Text>;
}
function Cell({state, children}: {state: string; children?: ReactNode}) {
  return <Stack gap={4} align="flex-start" miw={0} maw="100%"><VerdictState state={state}/>{children}</Stack>;
}

function TokenAccountRow({buy}: {buy: TokenAccountFirstBuy}) {
  return <Cell state={buy.state}>
    {buy.state === 'FOUND' && <TxAt label="Buy" signature={buy.signature} slot={buy.slot}/>}
    {buy.state === 'NO_BUY' && <Text size="sm">No buy of this token through this account.</Text>}
    {buy.state === 'NOT_ESTABLISHED' && <><Text size="sm">{reasonText(buy.reason)}</Text><Blocking signatures={buy.blocking}/></>}
  </Cell>;
}
function BoundTable({bound}: {bound: VerificationBound}) {
  return <Stack gap={4}>
    <Text size="sm">Limit reached: <Text span fw={600}>{BOUND_LIMITS[bound.limit]}</Text></Text>
    <Table withTableBorder verticalSpacing={2} horizontalSpacing="sm" fz="sm" maw={560}>
      <Table.Thead><Table.Tr>{['Listing','Pages','Entries','Oldest slot'].map(h=><Table.Th key={h}>{h}</Table.Th>)}</Table.Tr></Table.Thead>
      <Table.Tbody>{(Object.keys(LISTINGS) as VerificationListing[]).map(listing=><Table.Tr key={listing}><Table.Td>{LISTINGS[listing]}</Table.Td><Table.Td ff="monospace">{bound.pages[listing] ?? '—'}</Table.Td><Table.Td ff="monospace">{bound.entries[listing] ?? '—'}</Table.Td><Table.Td ff="monospace">{slotText(bound.oldestSlot[listing])}</Table.Td></Table.Tr>)}</Table.Tbody>
    </Table>
    <Exact label="Transaction fetches" value={bound.fetches}/>
  </Stack>;
}
function ActualFirstBuyRow({buy}: {buy: ActualFirstBuy}) {
  switch (buy.state) {
    case 'PROVEN': return <Cell state={buy.state}>
      {buy.claim && <Text size="sm" style={WRAPPING_STYLE}>{buy.claim}</Text>}
      <TxAt label="Buy" signature={buy.signature} slot={buy.slot}/>
      <TxAt label="Creation" signature={buy.creationSignature} slot={buy.creationSlot}/>
      {buy.creationTime && <Text size="sm"><Text span c="dimmed">Created </Text><Timestamp iso={buy.creationTime}/></Text>}
    </Cell>;
    case 'UNPROVEN': return <Cell state={buy.state}><Text size="sm">{reasonText(buy.reason)}</Text><Blocking signatures={buy.blocking}/>{buy.bound && <BoundTable bound={buy.bound}/>}</Cell>;
    case 'OUTSIDE_TOKEN_ACCOUNT': return <Cell state={buy.state}><TxAt label="Earlier buy" signature={buy.signature} slot={buy.slot}/><Text size="sm">{OUTSIDE_SENTENCE}</Text></Cell>;
    case 'NOT_VERIFIED': return <Cell state={buy.state}><Text size="sm">Not verified yet</Text>{buy.reason && <Text size="sm" c="dimmed">{reasonText(buy.reason)}</Text>}</Cell>;
  }
}
function DipRow({dip}: {dip: Verdict['dip']}) {
  if (dip.state !== 'QUALIFIED') return <Cell state={dip.state}><Text size="sm">{reasonText(dip.reason) ?? '—'}</Text></Cell>;
  return <Cell state={dip.state}><Exact label="Trigger slot" value={slotText(dip.triggerSlot)}/><Exact label="Trigger price" value={dip.triggerPrice}/><Exact label="Market cap (USD)" value={dip.marketCapUsd}/><Exact label="SOL/USDT rate" value={dip.rate}/></Cell>;
}
function ReplayRow({replay}: {replay: Verdict['replay']}) {
  return <Cell state={replay.state}>
    {replay.state === 'FAILED' && <><Exact label="Category" value={replay.category}/><Text size="sm">{replay.category === 'FIRST_BUY_MISMATCH' ? reasonText(replay.category) : 'Replay failed — this is not a strategy outcome'}</Text></>}
    {replay.state === 'NOT_RUN' && <Text size="sm">Runs automatically after export</Text>}
  </Cell>;
}
function EntryRow({entry}: {entry: NonNullable<Verdict['entry']>}) {
  return <Cell state={entry.state}>{entry.state === 'FILLED' ? <><Exact label="Slot" value={slotText(entry.slot)}/><Exact label="Execution price" value={entry.executionPrice}/></> : <Text size="sm">{reasonText(entry.reason) ?? '—'}</Text>}</Cell>;
}
function ExitRow({exit}: {exit: NonNullable<Verdict['exit']>}) {
  return <Cell state={exit.state}><Text size="sm">{reasonText(exit.reason)}</Text>{exit.state === 'CLOSED' && <><Exact label="Slot" value={slotText(exit.slot)}/><Exact label="Realized P&L (SOL)" value={exit.realizedPnl}/></>}</Cell>;
}

/** The wallet-token verdict: what was captured, which first buy is proven, and what the dip condition and replay did. */
export function TokenVerdict({target, verdict, onQualify, canQualify, disabled}: {target: TargetToken | null | undefined; verdict: Verdict | null | undefined; onQualify: () => void; canQualify: boolean; disabled: boolean}) {
  const classification = verdict ? CLASSIFICATIONS[verdict.classification] : null;
  const qualificationPending = verdict?.classification === 'INCOMPLETE_CAPTURE' && verdict.capture.reason === 'QUALIFICATION_PENDING';
  const pending = <Text size="sm" c="dimmed">Not evaluated yet. Inspect evidence to refresh the verdict.</Text>;
  const unverified = <Text size="sm" c="dimmed">Inspect evidence to verify.</Text>;
  const rows: Array<[ReactNode, ReactNode]> = [
    ['Capture', verdict ? <Cell state={verdict.capture.state}>{verdict.capture.stage && <Exact label="Stage" value={verdict.capture.stage}/>}{verdict.capture.reason && <Text size="sm">{reasonText(verdict.capture.reason)}</Text>}</Cell> : pending],
    [target ? <>Earliest buy through token account <ShortId value={target.tokenAccount}/></> : 'Earliest buy through token account', target ? <TokenAccountRow buy={target.tokenAccountFirstBuy}/> : unverified],
    ['Actual first Pump.fun curve buy', target ? <ActualFirstBuyRow buy={target.actualFirstBuy}/> : unverified],
    ['Dip condition', verdict ? <DipRow dip={verdict.dip}/> : pending],
    ['Replay', verdict ? <ReplayRow replay={verdict.replay}/> : pending],
  ];
  if (verdict?.entry) rows.push(['Entry', <EntryRow entry={verdict.entry}/>]);
  if (verdict?.exit) rows.push(['Exit', <ExitRow exit={verdict.exit}/>]);
  const provenance = verdict?.provenance;
  return <Section title="Token verdict" hint={target ? <>Mint <ShortId value={target.mint}/> · token program <ShortId value={target.tokenProgram}/></> : 'Wallet + token capture'} actions={<Group gap="sm">{classification && <Badge size="lg" variant="light" color={classification.color}>{qualificationPending ? 'Qualification pending' : classification.label}</Badge>}{canQualify && <Button variant="default" size="xs" onClick={onQualify} disabled={disabled}>Qualify</Button>}</Group>}>
    <Stack gap="sm">
      {classification && <Text size="sm">{qualificationPending ? reasonText(verdict?.capture.reason) : classification.detail}</Text>}
      <Table withRowBorders={false} verticalSpacing={4} horizontalSpacing="sm" className={styles.table}>
        <Table.Tbody>{rows.map(([label, value], index) => <Table.Tr key={index} className={styles.row}><Table.Th scope="row" fw={500} c="dimmed" className={styles.label}>{label}</Table.Th><Table.Td className={styles.value}>{value}</Table.Td></Table.Tr>)}</Table.Tbody>
      </Table>
      {provenance && (provenance.runId || provenance.datasetChecksum) && <Text size="xs" c="dimmed" ff="monospace" style={WRAPPING_STYLE}>Run {provenance.runId ?? '—'} · dataset {provenance.datasetChecksum ?? '—'} · configuration {provenance.configurationHash ?? '—'} · engine {provenance.engineVersion ?? '—'} · reference price {provenance.referencePrice ?? '—'}</Text>}
    </Stack>
  </Section>;
}
