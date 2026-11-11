-- One row per user per calendar day. Used by the DailyTracker app's two-way sync.
create table public.daily_progress (
  user_id      uuid        not null default auth.uid() references auth.users (id) on delete cascade,
  local_date   date        not null,
  completed    boolean     not null default false,
  description  text        not null default '' check (char_length(description) <= 5000),
  time_zone_id text        not null default 'UTC',
  updated_at   timestamptz not null default now(),
  revision     bigint      not null default 1,
  primary key (user_id, local_date)
);

comment on table public.daily_progress is
  'DailyTracker daily records. The app upserts on (user_id, local_date); the newest updated_at wins.';

-- Row level security: a signed-in user can only read and write their own rows.
alter table public.daily_progress enable row level security;

create policy "daily_progress_select_own"
  on public.daily_progress for select
  to authenticated
  using ((select auth.uid()) = user_id);

create policy "daily_progress_insert_own"
  on public.daily_progress for insert
  to authenticated
  with check ((select auth.uid()) = user_id);

create policy "daily_progress_update_own"
  on public.daily_progress for update
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "daily_progress_delete_own"
  on public.daily_progress for delete
  to authenticated
  using ((select auth.uid()) = user_id);

-- Nothing is exposed to signed-out visitors.
revoke all on public.daily_progress from anon;
grant select, insert, update, delete on public.daily_progress to authenticated;
