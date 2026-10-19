export const glossary = {
  'Market window':
    'The period of trades saved after a wallet buy, so the simulator can follow what happened next.',
  Slot: "Solana's clock tick, about 0.4 seconds. Used to order trades exactly.",
  Request:
    'One HTTP call to an API. A retry counts as another request; a cached transaction uses none.',
  Gap: 'A transaction we selected but could not download or decode. Gaps can stop a token from being replayable.',
  Cutoff:
    'When to stop saving trades in a market window: after the full window, or once the old default rules decide the result.',
  Validated:
    'The saved trades and rates passed the offline checks needed to build a replayable dataset.',
  'Case/Token': 'One token and its reference buy inside a capture.',
  Dataset: 'A saved set of recorded trades and rates that can be replayed.',
  Run: 'One simulation of a dataset with a particular configuration.',
  Rates: 'Recorded SOL/USDT prices used to calculate market cap and dollar values.',
  Recovery: 'Retrying the same capture stage within explicit total limits.',
  'Paper trade': 'A simulated buy or sell; it does not trade real funds.',
  'Market cap basis':
    'The recorded token price multiplied by the supported supply and reference rate.',
} as const;
export type GlossaryTerm = keyof typeof glossary;
