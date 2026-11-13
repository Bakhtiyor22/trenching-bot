-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying retry of failed
-- automation buys (docs/plans/20261107-automation-item-retry.md, units 1-2).

begin;

-- Times a person retried the item after it failed. The default gives every existing row 0 (and is what `ddl-auto: update` adds
-- locally), so no row is rewritten by hand.
alter table automation_items add column if not exists retries integer not null default 0;

-- The request id of the item's last retry; a repeat of that request changes nothing. Null until a retry.
alter table automation_items add column if not exists retry_request_id varchar(80);

commit;

-- Rollback: alter table automation_items drop column retries, drop column retry_request_id;
