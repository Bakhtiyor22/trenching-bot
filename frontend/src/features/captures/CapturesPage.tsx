import { Accordion, Anchor, Button, Group, NumberInput, ScrollArea, Select, SimpleGrid, Stack, Table, Tabs, Text, TextInput } from '@mantine/core';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { capturesApi, type CaptureConfig, type CaptureCreate, WALLET_TOKEN_SCOPE } from '../../api/captures';
import { ShortId, Timestamp } from '../../components/DisplayValues';
import { PageHeader, PagingFooter, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { isSolanaAddress } from '../../lib/base58';
import { paths } from '../../paths';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { CaptureStatus, CommandError } from './CaptureShared';
import { useCaptureCommand, useCaptureConfig, useCaptures, useDeleteCapture } from './captureQueries';

const SCOPE_HINTS: Record<string, string> = {
  'pumpfun-user-buys': 'Select supported Pump.fun buys made by this wallet.',
  'wallet-references': 'Address references can include activity by other users; they do not prove this wallet bought.',
  [WALLET_TOKEN_SCOPE]: 'Captures one wallet’s history with one token, then verifies the wallet’s actual first Pump.fun curve buy before market capture. Whether the case qualifies is only known after market capture.',
};
const monospaceInput = { input: { fontFamily: 'var(--mantine-font-family-monospace)' } };

function CreateCapture({config}: {config: CaptureConfig}) {
  const navigate = useNavigate();
  const [mode, setMode] = useState<string | null>('new');
  const [name, setName] = useState('');
  const [wallet, setWallet] = useState('');
  const [scope, setScope] = useState(config.walletDefaults.scope);
  const [limit, setLimit] = useState(config.walletDefaults.limit);
  const [scanPages, setScanPages] = useState(config.walletDefaults.scanPages);
  const [mint, setMint] = useState('');
  const walletToken = scope === WALLET_TOKEN_SCOPE;
  // Moving into or out of the wallet-token scope switches to that scope's sample defaults.
  const changeScope = (next: string) => {
    if ((next === WALLET_TOKEN_SCOPE) !== walletToken) {
      const defaults = next === WALLET_TOKEN_SCOPE ? config.walletTokenDefaults : config.walletDefaults;
      setLimit(defaults.limit); setScanPages(defaults.scanPages);
    }
    setScope(next);
  };
  const [walletPath, setWalletPath] = useState('');
  const [marketPath, setMarketPath] = useState('');
  const [ratesPath, setRatesPath] = useState('');
  const [validation, setValidation] = useState('');
  const create = useCaptureCommand<CaptureCreate, Awaited<ReturnType<typeof capturesApi.create>>>('create', capturesApi.create, result => { void navigate(paths.capture(result.id)); });
  const locked = create.isPending || !!create.pendingCommand;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) { setValidation('Enter a session name.'); return; }
    if (mode === 'new' && (!isSolanaAddress(wallet.trim()) || limit < 1 || limit > config.bounds.walletLimitMax || scanPages < 1 || scanPages > config.bounds.walletPagesMax)) { setValidation('Enter a valid wallet address and settings within the shown bounds.'); return; }
    if (mode === 'new' && walletToken && !isSolanaAddress(mint.trim())) { setValidation('Enter a valid token mint address.'); return; }
    if (mode === 'attach' && !walletPath.trim()) { setValidation('A wallet capture directory is required.'); return; }
    setValidation('');
    create.submit({ name: name.trim(), ...(mode === 'new' ? {wallet: {wallet: wallet.trim(), scope, limit, scanPages, ...(walletToken ? {mint: mint.trim()} : {})}} : {attach: {wallet: walletPath.trim(), ...(marketPath.trim() ? {market: marketPath.trim()} : {}), ...(ratesPath.trim() ? {rates: ratesPath.trim()} : {})}}) });
  };
  return <form onSubmit={submit}><Stack gap="md"><Tabs value={mode} onChange={setMode}><Tabs.List><Tabs.Tab value="new" disabled={locked}>New capture</Tabs.Tab><Tabs.Tab value="attach" disabled={locked}>Attach existing</Tabs.Tab></Tabs.List></Tabs><TextInput label="Session name" value={name} maxLength={120} disabled={locked} onChange={e => setName(e.currentTarget.value)} required />{mode === 'new' ? <><TextInput label="Wallet address" value={wallet} disabled={locked} onChange={e => setWallet(e.currentTarget.value)} styles={monospaceInput} autoComplete="off" spellCheck={false} required />{walletToken && <TextInput label="Token mint address" value={mint} disabled={locked} onChange={e => setMint(e.currentTarget.value)} styles={monospaceInput} autoComplete="off" spellCheck={false} required />}<SimpleGrid cols={{base:1, sm:2}}><Select label="Discovery scope" value={scope} disabled={locked} allowDeselect={false} onChange={v => changeScope(v ?? config.walletDefaults.scope)} data={[{value:'pumpfun-user-buys',label:'Pump.fun user buys'}, {value:'wallet-references',label:'Wallet references'}, {value:WALLET_TOKEN_SCOPE,label:'Wallet + token'}]} /><NumberInput label="Sample transactions" min={1} max={config.bounds.walletLimitMax} value={limit} disabled={locked} allowDecimal={false} onChange={v => setLimit(Number(v))} /></SimpleGrid><Text size="xs" c="dimmed">{SCOPE_HINTS[scope] ?? ''} Scope, sample size and wallet scan pages freeze when acquisition starts.</Text><Accordion variant="separated"><Accordion.Item value="advanced"><Accordion.Control>Advanced wallet settings</Accordion.Control><Accordion.Panel><NumberInput label="Wallet scan pages" value={scanPages} min={1} max={config.bounds.walletPagesMax} allowDecimal={false} disabled={locked} onChange={v=>setScanPages(Number(v))} /></Accordion.Panel></Accordion.Item></Accordion><Text size="sm" c="dimmed">Effective settings: {limit} transaction(s), {scanPages} scan pages. Choose the operation budget on the detail page before starting.</Text></> : <><Text size="sm" c="dimmed">Paths are relative to the backend capture root. Attachment verifies existing evidence offline and preserves its original location.</Text><TextInput label="Wallet capture directory" placeholder="my-wallet-capture" value={walletPath} onChange={e=>setWalletPath(e.currentTarget.value)} disabled={locked} required /><SimpleGrid cols={{base:1,sm:2}}><TextInput label="Market capture directory (optional)" value={marketPath} onChange={e=>setMarketPath(e.currentTarget.value)} disabled={locked}/><TextInput label="Rates capture directory (optional)" value={ratesPath} onChange={e=>setRatesPath(e.currentTarget.value)} disabled={locked}/></SimpleGrid></>}{validation && <Text c="red" role="alert" size="sm">{validation}</Text>}<CommandError error={create.error} uncertain={!!create.pendingCommand && !create.isPending} retry={create.retry}/><Group><Button type="submit" loading={create.isPending} disabled={!!create.pendingCommand}>{mode === 'new' ? 'Create session' : 'Attach and inspect'}</Button><Text size="xs" c="dimmed">Acquisition starts only when you explicitly start a stage.</Text></Group></Stack></form>;
}

export function CapturesPage() {
  const config = useCaptureConfig();
  const [page, setPage] = useState(0);
  const captures = useCaptures(page);
  const deleteCapture = useDeleteCapture();
  return <Stack gap="lg"><PageHeader title="Captures" description="Collect bounded evidence, verify coverage, then export a replay dataset."/><Section title="Add a capture">{config.isPending ? <LoadingState label="Loading capture settings…"/> : config.isError ? <ErrorState error={config.error} onRetry={()=>void config.refetch()}/> : <CreateCapture config={config.data}/>}</Section><Section title="Capture sessions">{captures.isPending ? <LoadingState/> : captures.isError ? <ErrorState error={captures.error} onRetry={()=>void captures.refetch()}/> : !captures.data.items.length ? <EmptyState title="No captures yet" description="Create a small wallet sample or attach existing CLI evidence."/> : <><ScrollArea><Table miw={650} verticalSpacing="sm" highlightOnHover><Table.Thead><Table.Tr><Table.Th>Name</Table.Th><Table.Th>Wallet</Table.Th><Table.Th>Latest operation</Table.Th><Table.Th>Created</Table.Th><Table.Th aria-label="Actions" /></Table.Tr></Table.Thead><Table.Tbody>{captures.data.items.map(item=><Table.Tr key={item.id}><Table.Td><Anchor component={Link} to={paths.capture(item.id)} fw={600}>{item.name}</Anchor></Table.Td><Table.Td>{item.wallet ? <ShortId value={item.wallet.wallet}/> : 'Attached evidence'}</Table.Td><Table.Td><Group gap="xs">{item.operation?.step}<CaptureStatus state={item.operation?.state ?? 'NOT_STARTED'}/></Group></Table.Td><Table.Td><Timestamp iso={item.createdAt}/></Table.Td><Table.Td><ConfirmDelete compact label="Delete" title="Delete this capture?" disabled={item.operation?.state==='RUNNING'} onConfirm={()=>deleteCapture.mutateAsync(item.id)}>Removes “{item.name}” and every file the console created for it: captured wallet, market and rates evidence, exports and calibration reports. Attached capture directories are only referenced and stay untouched, and datasets already imported stay in Datasets.</ConfirmDelete></Table.Td></Table.Tr>)}</Table.Tbody></Table></ScrollArea><PagingFooter page={page+1} pages={Math.max(1,Math.ceil(captures.data.total/20))} total={captures.data.total} onChange={p=>setPage(p-1)} noun="sessions"/></>}</Section></Stack>;
}
