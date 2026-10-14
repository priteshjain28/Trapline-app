-- Tests for 20261014000100_server_rules.sql. Run after smoke.sql (it reuses its people and projects).
\set ON_ERROR_STOP 1
set client_min_messages = warning;
reset role;

create or replace function pg_temp.ok(cond boolean, msg text) returns void language plpgsql as
$$ begin if cond is not true then raise exception 'FAIL: %', msg; end if; end $$;
create or replace function pg_temp.as_user(u uuid) returns void language sql as
$$ select set_config('request.jwt.claim.sub', u::text, false) $$;

-- ---------------------------------------------------------------- progress roll-up on the server
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
insert into tasks (id, project_id, name, unit, planned_qty)
  values ('80000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Brickwork', 'm2', 200);
insert into tasks (id, project_id, name)
  values ('80000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'Snagging');

-- the engineer and the owner log work on the same task from two phones; neither is lost
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
insert into task_updates (task_id, project_id, author_id, work_date, qty_done)
  values ('80000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', auth.uid(), '2026-10-12', 50);
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
insert into task_updates (id, task_id, project_id, author_id, work_date, qty_done)
  values ('81000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', auth.uid(), '2026-10-12', 30);
select pg_temp.ok((select done_qty = 80 and progress_pct = 40 and status = 'in_progress'
                   from tasks where id = '80000000-0000-0000-0000-000000000001'), 'quantities add up from both phones: 80 of 200 = 40%');

-- percent-based task takes the latest reported percent
insert into task_updates (task_id, project_id, author_id, work_date, progress_pct)
  values ('80000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', auth.uid(), '2026-10-11', 70),
         ('80000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', auth.uid(), '2026-10-12', 60);
select pg_temp.ok((select progress_pct = 60 from tasks where id = '80000000-0000-0000-0000-000000000002'), 'latest percent wins');

-- deleting a wrong update recomputes
delete from task_updates where id = '81000000-0000-0000-0000-000000000002';
select pg_temp.ok((select done_qty = 50 and progress_pct = 25 from tasks where id = '80000000-0000-0000-0000-000000000001'), 'deleted update recomputed');

-- finishing the quantity completes the task; project progress is the mean of its tasks
insert into task_updates (task_id, project_id, author_id, work_date, qty_done)
  values ('80000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', auth.uid(), '2026-10-13', 160);
select pg_temp.ok((select progress_pct = 100 and status = 'completed' from tasks where id = '80000000-0000-0000-0000-000000000001'),
                  'over-delivery caps at 100% and completes');
select pg_temp.ok((select progress_pct = round((0 + 0 + 100 + 60) / 4.0, 2) from projects where id = '10000000-0000-0000-0000-000000000001'),
                  'project progress = mean of its 4 tasks');

-- ---------------------------------------------------------------- plan limits
insert into projects (org_id, name, created_by) select org_id, 'third site', auth.uid() from projects where id = '10000000-0000-0000-0000-000000000001';
do $$ begin
  insert into projects (org_id, name, created_by) select org_id, 'fourth site', auth.uid() from projects where id = '10000000-0000-0000-0000-000000000001';
  raise exception 'FAIL: free plan allowed a 4th active project';
exception when raise_exception then
  if sqlerrm <> 'plan_limit:projects' then raise; end if;
end $$;
update projects set status = 'archived' where name = 'third site';
insert into projects (org_id, name, created_by) select org_id, 'fourth site', auth.uid() from projects where id = '10000000-0000-0000-0000-000000000001';
select pg_temp.ok(true, 'archived projects free a slot');

-- ---------------------------------------------------------------- storage access follows the project
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
insert into storage.objects (bucket_id, name) values ('project-media', '10000000-0000-0000-0000-000000000001/photo-1.jpg');
select pg_temp.ok((select count(*) = 1 from storage.objects), 'engineer uploads to their site');
do $$ begin
  insert into storage.objects (bucket_id, name) values ('project-media', '10000000-0000-0000-0000-000000000002/photo-2.jpg');
  raise exception 'FAIL: engineer uploaded to a site they are not on';
exception when insufficient_privilege then null; end $$;
do $$ begin
  insert into storage.objects (bucket_id, name) values ('project-media', 'not-a-project/photo.jpg');
  raise exception 'FAIL: upload outside a project folder';
exception when insufficient_privilege then null; end $$;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
select pg_temp.ok((select count(*) = 0 from storage.objects), 'outsider sees no files');

-- ---------------------------------------------------------------- rate limits are server-only
do $$ begin
  perform public.rate_limit_hit('invite:x', 2, 3600);
  raise exception 'FAIL: app users can call rate_limit_hit';
exception when insufficient_privilege then null; end $$;
reset role;
select pg_temp.ok(public.rate_limit_hit('invite:x', 2, 3600) and public.rate_limit_hit('invite:x', 2, 3600)
                  and not public.rate_limit_hit('invite:x', 2, 3600), 'third hit in the window is refused');

-- ---------------------------------------------------------------- notifications from jobs
select pg_temp.ok(app.daily_nudge('2026-10-20') >= 1, 'nudge for a quiet day');
select pg_temp.ok(app.daily_nudge('2026-10-20') = 0, 'only one nudge per manager per day');
select app.daily_nudge('2026-10-12');   -- palak logged work on the 12th
select pg_temp.ok((select count(*) = 0 from notifications where kind = 'daily_nudge' and payload->>'day' = '2026-10-12'
                   and project_id = '10000000-0000-0000-0000-000000000001'), 'no nudge for a site that logged work');
update tasks set due_date = '2026-10-19' where id = '80000000-0000-0000-0000-000000000002';
select pg_temp.ok(app.overdue_task_alerts('2026-10-20') = 1, 'overdue task alert to the manager');

-- ---------------------------------------------------------------- account deletion
set role authenticated;
-- A is the only owner of an org with B in it: refused, nothing deleted
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
do $$ begin
  perform public.delete_my_account();
  raise exception 'FAIL: sole owner deleted with members left';
exception when raise_exception then
  if sqlerrm <> 'owner_must_transfer' then raise; end if;
end $$;
-- C belongs to nothing: deleted
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
select public.delete_my_account();
reset role;
select pg_temp.ok((select count(*) = 0 from profiles where id = '00000000-0000-0000-0000-00000000000c'), 'outsider deleted');
-- D owns an org alone: the org goes too
insert into auth.users (id, phone) values ('00000000-0000-0000-0000-00000000000d', '910000000004');
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000d');
select public.create_organisation('Solo Builders');
select public.delete_my_account();
reset role;
select pg_temp.ok((select count(*) = 0 from organisations where name = 'Solo Builders'), 'sole-member org deleted with its owner');
-- B leaves: their updates stay, author cleared
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
select public.delete_my_account();
reset role;
select pg_temp.ok((select count(*) >= 1 from task_updates where author_id is null), 'work logged by a deleted user stays');
select pg_temp.ok((select count(*) = 0 from project_members where user_id = '00000000-0000-0000-0000-00000000000b'), 'memberships gone');

select 'server rules: ok';
