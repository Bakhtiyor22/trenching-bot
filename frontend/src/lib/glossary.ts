export const glossary = {
  'Market window': 'The recorded trades watched after the wallet’s reference buy.',
  Slot: 'A position in Solana’s sequence of blocks; the simulator orders trades by slot.',
  Request: 'One call sent to a provider; retries each use another request.',
  Gap: 'A selected transaction whose required details could not be recorded.',
  Cutoff: 'The rule deciding when a market window has enough recorded history.',
  Validated: 'The recorded evidence and reference rates passed the replay checks.',
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
