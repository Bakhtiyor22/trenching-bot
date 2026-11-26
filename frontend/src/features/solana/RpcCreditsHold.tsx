import { Alert, Anchor, Stack, Text } from '@mantine/core';
import { Link } from 'react-router';
import { useSolanaAccounts } from '../../api/queries';
import type { RpcAccounts } from '../../api/types';
import { IconAlert } from '../../components/Icons';
import { paths } from '../../paths';
import { awaitsNetworkCheck, isCaptureHeld } from './rpcAccounts';

/**
 * Why capture is held while no account can take requests, in the words of the Telegram message that announces it.
 * `link` adds the way to the Solana page, for pages other than that one.
 */
export function CreditsHoldAlert({ data, link = false }: { data: RpcAccounts; link?: boolean }) {
  if (!isCaptureHeld(data)) return null;
  return (
    <Alert
      color="orange"
      variant="light"
      title="Capture is held until an account has credits again"
      icon={<IconAlert />}
    >
      <Stack gap={6}>
        <Text size="sm">
          No RPC account can take requests. A wallet or market capture cannot start, and the
          automation keeps each buy at its step with no attempt charged. Capture goes on by itself
          once an account has credits. An operation that was running when the credits ran out fails
          that attempt, like any provider failure.
        </Text>
        {awaitsNetworkCheck(data) && (
          <Text size="sm">
            The backup has credits but is not used until it has been checked as Solana mainnet. That
            check runs shortly after the backend starts and again at each balance check.
          </Text>
        )}
        {link && (
          <Anchor component={Link} to={paths.solana} size="sm">
            See both accounts on the Solana page
          </Anchor>
        )}
      </Stack>
    </Alert>
  );
}

/**
 * The hold as pages other than the Solana page show it. The automation API reports no hold of its own, so the
 * accounts read is what says no account can take requests; while it is loading or failing, nothing is shown.
 */
export function RpcCreditsHold() {
  const accounts = useSolanaAccounts();
  return accounts.data ? <CreditsHoldAlert data={accounts.data} link /> : null;
}
