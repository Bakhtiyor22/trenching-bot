import { Button, Group, List, Modal, Stack, Text, Tooltip, VisuallyHidden } from '@mantine/core';
import { useDisclosure, useId } from '@mantine/hooks';
import type { ReactNode } from 'react';
import {
  useRetryDay,
  useRetryItem,
  type AutomationDayRetryResult,
  type AutomationItem,
  type IsoDate,
} from '../../api/automation';
import { Mint, ShortId } from '../../components/DisplayValues';
import { formatCount } from '../../lib/format';
import {
  itemRefusalText,
  RETRY_SPEND_NOTE,
  retryErrorText,
  retryRefusalText,
  zoneTime,
} from './automationModel';

/**
 * Retry controls for failed automation buys. A retry sends a buy back to the step where it failed and spends provider
 * requests under the same 24 h cap, so each click is a deliberate, new request.
 */

function plural(n: number, one: string, many: string): string {
  return `${formatCount(n)} ${n === 1 ? one : many}`;
}

/**
 * A disabled-looking button that stays focusable, so its tooltip (the reason) is reachable by keyboard; the reason is
 * also its accessible description.
 */
function RefusedButton({
  label,
  reason,
  children,
}: {
  label?: string;
  reason: string;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <>
      <Tooltip label={reason} multiline w={300}>
        <Button
          variant="default"
          size="xs"
          data-disabled
          aria-disabled
          aria-label={label}
          aria-describedby={id}
          onClick={(event) => event.preventDefault()}
        >
          {children}
        </Button>
      </Tooltip>
      <VisuallyHidden id={id}>{reason}</VisuallyHidden>
    </>
  );
}

/**
 * The Retry button of one failed buy; nothing for a buy that is not failed. `label` names the buy for a row button.
 * With `hint`, the reason, the error or the hint is written beside the button (the selected-buy panel); without it,
 * a refusal shows as the button's tooltip and an error below it (a table row).
 */
export function RetryBuy({
  item,
  label,
  hint,
}: {
  item: AutomationItem;
  label?: string;
  hint?: ReactNode;
}) {
  const retry = useRetryItem(item.id);
  if (item.state !== 'FAILED') return null;
  const refusal = itemRefusalText(item);
  const error = retry.isError ? retryErrorText(retry.error) : null;
  const button = refusal ? (
    hint === undefined ? (
      <RefusedButton label={label} reason={refusal}>
        Retry
      </RefusedButton>
    ) : (
      <Button variant="default" size="xs" disabled aria-label={label}>
        Retry
      </Button>
    )
  ) : (
    <Button
      variant="default"
      size="xs"
      loading={retry.isPending}
      aria-label={label}
      onClick={() => retry.mutate()}
    >
      Retry
    </Button>
  );
  if (hint === undefined)
    return (
      <Stack gap={4} align="flex-end">
        {button}
        {error && (
          <Text size="xs" c="red" role="alert" maw={260} ta="right">
            {error}
          </Text>
        )}
      </Stack>
    );
  return (
    <Group gap="sm" wrap="nowrap" align="flex-start">
      {button}
      {error ? (
        <Text size="sm" c="red" role="alert">
          {error}
        </Text>
      ) : (
        <Text size="sm" c="dimmed">
          {refusal ? `Cannot be retried: ${refusal}` : hint}
        </Text>
      )}
    </Group>
  );
}

function BuyName({ id, items, zone }: { id: string; items: AutomationItem[]; zone: string }) {
  const item = items.find((i) => i.id === id);
  if (!item) return <ShortId value={id} />;
  return (
    <>
      <Text span ff="monospace" size="sm">
        {zoneTime(item.blockTime, zone, true)}
      </Text>{' '}
      <Mint mint={item.mint} />
    </>
  );
}

function DayRetryResult({
  result,
  items,
  zone,
}: {
  result: AutomationDayRetryResult;
  items: AutomationItem[];
  zone: string;
}) {
  const refused = Object.entries(result.refused);
  const nothing = result.retried.length + result.unchanged.length + refused.length === 0;
  return (
    <Stack gap="xs" aria-label="Retry result">
      {nothing ? (
        <Text size="sm">No failed buys were left on this day, so nothing was retried.</Text>
      ) : (
        <Text size="sm">
          {result.retried.length > 0
            ? `${plural(result.retried.length, 'buy', 'buys')} sent back to the step where it failed.`
            : 'No buy was sent back.'}
          {result.unchanged.length > 0 &&
            ` ${plural(result.unchanged.length, 'buy was', 'buys were')} already retried by this request and left as ${result.unchanged.length === 1 ? 'it is' : 'they are'}.`}
        </Text>
      )}
      {refused.length > 0 && (
        <>
          <Text size="sm">{plural(refused.length, 'buy was', 'buys were')} not retried:</Text>
          <List size="sm" spacing={4}>
            {refused.map(([id, code]) => (
              <List.Item key={id}>
                <BuyName id={id} items={items} zone={zone} /> —{' '}
                {retryRefusalText(code, items.find((i) => i.id === id)?.failureCode)}
              </List.Item>
            ))}
          </List>
        </>
      )}
    </Stack>
  );
}

/**
 * "Retry failed buys" for trading day `date`, after a confirmation that says what it costs; the dialog then shows what
 * the backend did. Nothing for a day without failed buys.
 */
export function RetryFailedBuys({
  date,
  items,
  zone,
}: {
  date: IsoDate;
  items: AutomationItem[];
  zone: string;
}) {
  const [opened, { open, close }] = useDisclosure(false);
  const retry = useRetryDay(date);
  const sending = retry.isPending;
  const failed = items.filter((i) => i.state === 'FAILED');
  const retryable = failed.filter((i) => i.retryRefusal == null);
  if (failed.length === 0 && !opened) return null;
  if (retryable.length === 0 && !opened)
    return (
      <RefusedButton
        reason={`None of this day's ${plural(failed.length, 'failed buy', 'failed buys')} can be retried now. Choose a buy to see why.`}
      >
        Retry failed buys
      </RefusedButton>
    );
  const dismiss = () => {
    if (!sending) close();
  };
  const left = failed.length - retryable.length;
  return (
    <>
      <Button
        variant="default"
        size="xs"
        onClick={() => {
          retry.reset();
          open();
        }}
      >
        Retry failed buys
      </Button>
      <Modal
        opened={opened}
        onClose={dismiss}
        closeOnClickOutside={!sending}
        closeOnEscape={!sending}
        withCloseButton={!sending}
        title={
          retry.isSuccess
            ? 'Retry sent'
            : `Retry ${plural(retryable.length, 'failed buy', 'failed buys')}?`
        }
        centered
      >
        <Stack gap="md">
          {retry.isSuccess ? (
            <DayRetryResult result={retry.data} items={items} zone={zone} />
          ) : (
            <Text size="sm">
              Each buy goes back to the step where it failed, keeping the evidence already saved,
              and gets a fresh round of attempts. {RETRY_SPEND_NOTE}
              {left > 0 &&
                ` ${plural(left, 'other failed buy', 'other failed buys')} cannot be retried and ${left === 1 ? 'stays' : 'stay'} as ${left === 1 ? 'it is' : 'they are'}.`}
            </Text>
          )}
          {retry.isError && (
            <Text size="sm" c="red" role="alert">
              {retryErrorText(retry.error)}
            </Text>
          )}
          <Group justify="flex-end">
            {retry.isSuccess ? (
              <Button onClick={close}>Done</Button>
            ) : (
              <>
                <Button variant="default" onClick={dismiss} disabled={sending}>
                  Cancel
                </Button>
                <Button loading={sending} onClick={() => retry.mutate()}>
                  Retry {plural(retryable.length, 'buy', 'buys')}
                </Button>
              </>
            )}
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
