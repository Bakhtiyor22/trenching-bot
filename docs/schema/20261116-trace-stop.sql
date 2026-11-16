-- Prod schema for docs/plans/20261116-automation-trace-stop-console-restructure.md.
-- Every statement is additive. Every profile (local, test and prod, which is the Oracle server) runs ddl-auto: update, so
-- Hibernate creates these tables, columns and indexes from the entities at startup. Nothing needs applying by hand.
-- This file documents them, and is for a database that only validates.

-- Phase 1, server-side run search (replay module)

-- The anchor buy's block time of the run's dataset, and when the run's search index was written (null = not indexed yet).
alter table simulation_runs add column anchor_block_time timestamptz;
alter table simulation_runs add column search_indexed_at timestamptz;
-- When the startup backfill found the run's dataset unreadable and gave up on it (null = it did not). Such a run stays unindexed
-- (search_indexed_at null) and is not read again. To read it again after fixing its dataset: update simulation_runs set search_index_failed_at = null where id = '...';
alter table simulation_runs add column search_index_failed_at timestamptz;
create index idx_simulation_runs_anchor on simulation_runs (anchor_block_time, id);

-- The tokens each run's dataset contains: its pair base mints plus the anchor buy's mint.
create table simulation_run_tokens (
  run_id varchar(64) not null,
  mint varchar(44) not null,
  primary key (run_id, mint)
);
create index idx_simulation_run_tokens_mint on simulation_run_tokens (mint, run_id);

-- Runs stored before this change have search_indexed_at null. The application indexes them once at startup (200 runs per
-- transaction, logging "run search index backfilled runs= skipped= failed="), in every profile except test. Rerunning it
-- changes nothing. To check progress:
--   select count(*) from simulation_runs where search_indexed_at is null and search_index_failed_at is null;
-- and to list the runs it gave up on: select id from simulation_runs where search_index_failed_at is not null;

-- Rollback: drop table simulation_run_tokens; drop index idx_simulation_runs_anchor;
--   alter table simulation_runs drop column anchor_block_time, drop column search_indexed_at, drop column search_index_failed_at;

-- Phase 1, token flow history (console.automation)

-- Append-only history of each automation item, written in the transaction of the change it records. seq is gapless per item from 1.
create table automation_item_events (id varchar(36) primary key, item_id varchar(64) not null, seq integer not null,
  at timestamptz not null, kind varchar(32) not null, from_state varchar(24), to_state varchar(24), step varchar(16),
  attempt integer, capture_session_id varchar(80), operation_id varchar(80), requests_used integer,
  failure_code varchar(64), failure_message varchar(512), actor varchar(80), request_id varchar(80), note varchar(200),
  detail jsonb, constraint uq_automation_item_events_seq unique (item_id, seq));
create index idx_automation_item_events_at on automation_item_events (at);

-- Items stored before this change have no event. Each one gets a HISTORY_STARTED event the first time it changes (the writer adds it
-- ahead of the change's events), and the application gives one to each idle item once at startup (one item per transaction, logging
-- "automation history started items= skipped= failed="), in the local profile with the console on and never in test. Rerunning it
-- changes nothing. kind also holds REPLACED, which an item gets when an earlier buy takes its place; no schema change. To check progress:
--   select count(*) from automation_items i where not exists (select 1 from automation_item_events e where e.item_id = i.id);

-- Rollback: drop table automation_item_events;
