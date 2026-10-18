-- Reference only: no Flyway runs this. Production (`ddl-auto: validate`) needs it applied by hand before deploying API
-- usage tracking (docs/plans/archive/20261016-console-capture-run-ux.md). The application records usage in every
-- profile, so `prod` refuses to start without this table. Local and test databases get it from `ApiUsageBucketEntity`
-- through `ddl-auto: update`; the CHECK constraint below exists only here, like the other constraints Hibernate does
-- not create.
--
-- One row per 5-minute UTC bucket, provider, operation, task and capture (`''` when none). `id` is the SHA-256 hex of
-- that identity. Flushes only add to the counters, so the table is new and additive and nothing existing changes.

create table api_usage_buckets (
    id varchar(64) primary key,
    bucket_start timestamptz not null,
    provider varchar(16) not null,
    operation varchar(64) not null,
    task varchar(24) not null,
    capture_scope varchar(80) not null,
    requests bigint not null default 0,
    failed_requests bigint not null default 0,
    rate_limited_responses bigint not null default 0,
    cache_hits bigint not null default 0,
    response_bytes bigint not null default 0,
    last_call_at timestamptz,
    constraint uq_api_usage_bucket_identity unique (bucket_start, provider, operation, task, capture_scope),
    constraint ck_api_usage_nonnegative check (
        requests >= 0 and failed_requests >= 0 and rate_limited_responses >= 0 and cache_hits >= 0 and response_bytes >= 0)
);

create index idx_api_usage_buckets_start on api_usage_buckets (bucket_start);
create index idx_api_usage_buckets_capture on api_usage_buckets (capture_scope, bucket_start);
