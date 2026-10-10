import { Anchor, Group } from '@mantine/core';
import type { MarketLink } from '../lib/marketLinks';

/** Outside links, each in a new tab with no referrer; nothing is fetched from those sites until clicked. */
export function MarketLinks({ links }: { links: MarketLink[] }) {
  if (links.length === 0) return null;
  return (
    <Group gap={8} component="span" style={{ display: 'inline-flex' }}>
      {links.map((link) => (
        <Anchor key={link.href} href={link.href} target="_blank" rel="noopener noreferrer" size="xs">
          {link.label}
        </Anchor>
      ))}
    </Group>
  );
}
