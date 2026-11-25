import { Stack, Text, Tooltip, type TextProps } from '@mantine/core';
import type { SolUsdtPrice } from '../api/types';
import { formatSignedDecimal, trimFractionZeros, truncateDecimal } from '../lib/decimal';
import { rateTime, USDT_PLACES, usdtNow } from '../lib/usdt';

/**
 * "≈ +1.69 USDT": a SOL amount at the live rate, signed and cut to 2 places toward zero, with the exact product, the
 * rate and its time in the tooltip. `now` adds " now" for a total; nothing renders without a rate.
 */
export function UsdtNow({
  sol,
  price,
  now = false,
  scope = 'run',
  formatTime = rateTime,
  ...textProps
}: {
  sol: string;
  price: SolUsdtPrice | null | undefined;
  now?: boolean;
  /** A run's own row, or a total over many runs: the tooltip points to where the trade-time USDT is. */
  scope?: 'run' | 'total';
  /** The rate's time as the page shows times: the viewer's clock by default, the automation clock on its pages. */
  formatTime?: (asOf: string | null | undefined) => string | null;
} & TextProps) {
  const product = usdtNow(sol, price);
  if (!product || !price?.rate) return null;
  const time = formatTime(price.asOf);
  return (
    <Tooltip
      withArrow
      multiline
      maw={320}
      events={{ hover: true, focus: true, touch: true }}
      label={
        <Stack gap={2}>
          <Text size="xs" ff="monospace">
            {formatSignedDecimal(trimFractionZeros(product))} USDT
          </Text>
          <Text size="xs">
            1 SOL = {trimFractionZeros(price.rate)} USDT, Binance{time ? `, ${time}` : ''}
          </Text>
          <Text size="xs">
            {scope === 'run'
              ? "The run's page shows USDT at each trade's own rate."
              : "Each run's own page shows USDT at its trades' own rates."}
          </Text>
        </Stack>
      }
    >
      <Text
        span
        size="xs"
        ff="monospace"
        c="dimmed"
        style={{ whiteSpace: 'nowrap' }}
        tabIndex={0}
        {...textProps}
      >
        ≈ {formatSignedDecimal(truncateDecimal(product, USDT_PLACES))} USDT{now ? ' now' : ''}
      </Text>
    </Tooltip>
  );
}
