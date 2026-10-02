-- Execution and exit prices keep the observed price scale (numeric(78,38)) instead of
-- the 12-place quote-amount scale, which truncated memecoin prices to a few digits.
alter table paper_fills alter column execution_price type numeric(78,38);
alter table positions alter column exit_price type numeric(78,38);
