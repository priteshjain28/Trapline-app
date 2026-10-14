-- Server-side rules the app relies on:
--   * profiles created from auth.users (phone sign-in)
--   * task progress and project progress computed on the server from task_updates (two phones can't lose each other's work)
--   * plan limits, rate limits, account deletion
--   * storage buckets and their access rules
--   * notifications for the daily nudge and overdue tasks (scheduled in the jobs migration)

-- ---------------------------------------------------------------- profiles from auth.users

create or replace function app.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into profiles (id, phone, email)
  values (
    new.id,
    -- Supabase stores phone numbers without the leading +
    case when coalesce(new.phone, '') = '' then null else '+' || ltrim(new.phone, '+') end,
    nullif(new.email, '')
  )
  on conflict (id) do nothing;
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function app.handle_new_user();

-- ---------------------------------------------------------------- progress roll-up

-- Quantity wins when the task has a planned quantity; otherwise the latest reported percent.
-- Same rule as Progress.apply() in the app, which shows it optimistically before sync.
create or replace function app.recompute_task(p_task uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  t tasks;
  v_done numeric;
  v_has_qty boolean;
  v_pct numeric;
begin
  select * into t from tasks where id = p_task;
  if not found then return; end if;
  select coalesce(sum(qty_done), 0), bool_or(qty_done is not null) into v_done, v_has_qty
  from task_updates where task_id = p_task;

  if t.planned_qty is not null and coalesce(v_has_qty, false) then
    v_pct := least(100, round(v_done / t.planned_qty * 100));
  else
    select progress_pct into v_pct from task_updates
    where task_id = p_task and progress_pct is not null
    order by work_date desc, created_at desc limit 1;
  end if;
  v_pct := coalesce(v_pct, 0);

  update tasks set
    done_qty = v_done,
    progress_pct = v_pct,
    status = case
      when v_pct >= 100 then 'completed'
      when t.status = 'blocked' then 'blocked'
      when v_pct > 0 or v_done > 0 then 'in_progress'
      else 'not_started'
    end
  where id = p_task;

  perform app.recompute_project(t.project_id);
end $$;

create or replace function app.recompute_project(p_project uuid) returns void
language sql security definer set search_path = public as $$
  update projects set progress_pct = coalesce((select round(avg(progress_pct), 2) from tasks where project_id = p_project), 0)
  where id = p_project;
$$;

create or replace function app.on_task_update_change() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform app.recompute_task(coalesce(new.task_id, old.task_id));
  if tg_op = 'UPDATE' and new.task_id <> old.task_id then perform app.recompute_task(old.task_id); end if;
  return coalesce(new, old);
end $$;

create trigger recompute_progress after insert or update or delete on task_updates
  for each row execute function app.on_task_update_change();

create or replace function app.on_task_change() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' then
    perform app.recompute_task(new.id);          -- planned quantity changed
  else
    perform app.recompute_project(coalesce(new.project_id, old.project_id));
  end if;
  return coalesce(new, old);
end $$;

create trigger recompute_on_plan after update of planned_qty on tasks
  for each row execute function app.on_task_change();
create trigger recompute_on_add_remove after insert or delete on tasks
  for each row execute function app.on_task_change();

-- ---------------------------------------------------------------- plan limits

-- Free organisations get a few active sites. Raised as 'plan_limit:<what>' so the app can explain it.
create or replace function app.plan_limit(p_plan text, p_what text) returns integer
language sql immutable as $$
  select case p_what
    when 'projects' then case p_plan when 'free' then 3 when 'pro' then 25 else null end
    else null
  end;
$$;

create or replace function app.check_project_limit() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_plan text; v_limit integer; v_count integer;
begin
  select plan into v_plan from organisations where id = new.org_id;
  v_limit := app.plan_limit(v_plan, 'projects');
  if v_limit is null then return new; end if;
  select count(*) into v_count from projects where org_id = new.org_id and status <> 'archived';
  if v_count >= v_limit then
    raise exception 'plan_limit:projects' using errcode = 'P0001', hint = v_limit::text;
  end if;
  return new;
end $$;

create trigger check_project_limit before insert on projects
  for each row execute function app.check_project_limit();

-- ---------------------------------------------------------------- rate limits (for functions that send SMS)

create table rate_limits (
  key text not null,
  window_start timestamptz not null,
  hits integer not null default 0,
  primary key (key, window_start)
);
alter table rate_limits enable row level security;   -- no policies: service role only

-- true = allowed. Fixed windows; old rows are cleaned by the jobs migration.
create or replace function public.rate_limit_hit(p_key text, p_max integer, p_window_seconds integer) returns boolean
language plpgsql security definer set search_path = public as $$
declare v_start timestamptz := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
        v_hits integer;
begin
  insert into rate_limits (key, window_start, hits) values (p_key, v_start, 1)
  on conflict (key, window_start) do update set hits = rate_limits.hits + 1
  returning hits into v_hits;
  return v_hits <= p_max;
end $$;
revoke execute on function public.rate_limit_hit(text, integer, integer) from public, anon, authenticated;

-- ---------------------------------------------------------------- account deletion

-- Deletes the caller's profile and memberships. Organisations they own alone are deleted with them.
-- Refuses if they are the only owner of an organisation that still has other people in it.
-- The delete-account function then removes the auth user.
create or replace function public.delete_my_account() returns void
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); o record;
begin
  if v_uid is null then raise exception 'not signed in'; end if;
  for o in select m.org_id from org_members m where m.user_id = v_uid and m.role = 'owner' loop
    if exists (select 1 from org_members where org_id = o.org_id and role = 'owner' and user_id <> v_uid) then
      continue;                                  -- another owner stays
    elsif exists (select 1 from org_members where org_id = o.org_id and user_id <> v_uid) then
      raise exception 'owner_must_transfer' using errcode = 'P0001', hint = o.org_id::text;
    else
      delete from organisations where id = o.org_id;
    end if;
  end loop;
  delete from profiles where id = v_uid;
end $$;

-- ---------------------------------------------------------------- storage

-- Files live under <project_id>/... in each bucket, so access follows the project.
create or replace function app.path_project(p_name text) returns uuid
language sql immutable as $$
  select case when split_part(p_name, '/', 1) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              then split_part(p_name, '/', 1)::uuid end;
$$;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('project-media', 'project-media', false, 104857600, array['image/jpeg', 'image/png', 'image/webp', 'video/mp4']),
  ('project-drive', 'project-drive', false, 104857600, null),
  ('reports', 'reports', false, 20971520, array['application/pdf'])
on conflict (id) do nothing;

create policy "site files: read" on storage.objects for select to authenticated
  using (bucket_id in ('project-media', 'project-drive', 'reports') and app.can_read(app.path_project(name)));
create policy "site files: add" on storage.objects for insert to authenticated
  with check (bucket_id in ('project-media', 'project-drive', 'reports') and app.can_write(app.path_project(name)));
create policy "site files: replace" on storage.objects for update to authenticated
  using (bucket_id in ('project-media', 'project-drive', 'reports') and app.can_write(app.path_project(name)));
create policy "site files: delete" on storage.objects for delete to authenticated
  using (bucket_id in ('project-media', 'project-drive', 'reports') and app.can_write(app.path_project(name)));

-- ---------------------------------------------------------------- notifications made by jobs

-- 18:30 site time: managers of active projects with nothing logged today get one nudge.
-- (The original raises a support "issue" inside the project instead; recon S31.)
create or replace function app.daily_nudge(p_day date default (now() at time zone 'Asia/Kolkata')::date) returns integer
language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  insert into notifications (user_id, project_id, kind, payload)
  select pm.user_id, p.id, 'daily_nudge', jsonb_build_object('project', p.name, 'day', p_day)
  from projects p
  join project_members pm on pm.project_id = p.id and pm.role = 'manager'
  where p.status = 'active'
    and not exists (select 1 from task_updates u where u.project_id = p.id and u.work_date = p_day)
    and not exists (select 1 from attendance a where a.project_id = p.id and a.work_date = p_day)
    and not exists (select 1 from notifications x where x.user_id = pm.user_id and x.project_id = p.id
                    and x.kind = 'daily_nudge' and x.payload->>'day' = p_day::text);
  get diagnostics n = row_count;
  return n;
end $$;

-- 06:00 site time: assignees (or managers when unassigned) hear about tasks that just went overdue.
create or replace function app.overdue_task_alerts(p_day date default (now() at time zone 'Asia/Kolkata')::date) returns integer
language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  insert into notifications (user_id, project_id, kind, payload)
  select distinct coalesce(t.assignee_id, pm.user_id), t.project_id, 'task_overdue',
         jsonb_build_object('task', t.id, 'name', t.name, 'due', t.due_date)
  from tasks t
  left join project_members pm on t.assignee_id is null and pm.project_id = t.project_id and pm.role = 'manager'
  where t.status <> 'completed' and t.due_date = p_day - 1
    and coalesce(t.assignee_id, pm.user_id) is not null;
  get diagnostics n = row_count;
  return n;
end $$;
