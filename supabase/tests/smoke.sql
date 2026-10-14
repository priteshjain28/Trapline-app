-- Smoke test for the initial migration. Run on a throwaway database:
--   psql -d site -f local_auth_stub.sql -f ../migrations/20261014000000_init.sql -f smoke.sql
-- Every check raises on failure; the last line prints "smoke: ok".
\set ON_ERROR_STOP 1
set client_min_messages = warning;

-- Grants come from the default privileges in local_auth_stub.sql, as on Supabase.

create function pg_temp.ok(cond boolean, msg text) returns void language plpgsql as
$$ begin if cond is not true then raise exception 'FAIL: %', msg; end if; end $$;

create function pg_temp.as_user(u uuid) returns void language sql as
$$ select set_config('request.jwt.claim.sub', u::text, false) $$;

-- people: A owner, B engineer (invited), C outsider
-- phone sign-in creates the auth user; a trigger creates the profile (Supabase stores phones without '+')
insert into auth.users (id, phone) values
  ('00000000-0000-0000-0000-00000000000a', '910000000001'),
  ('00000000-0000-0000-0000-00000000000b', '910000000002'),
  ('00000000-0000-0000-0000-00000000000c', '910000000003');
select pg_temp.ok((select count(*) = 3 from profiles where phone like '+91%'), 'profiles created from auth users');
update profiles set name = 'Owner A' where id = '00000000-0000-0000-0000-00000000000a';
update profiles set name = 'Engineer B' where id = '00000000-0000-0000-0000-00000000000b';
update profiles set name = 'Outsider C' where id = '00000000-0000-0000-0000-00000000000c';

set role authenticated;

-- ---------------------------------------------------------------- org + project as A
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
create temp table ctx as select public.create_organisation('My Organisation') as org;
insert into projects (id, org_id, name, created_by)
  select '10000000-0000-0000-0000-000000000001', org, 'palak', auth.uid() from ctx;
insert into projects (id, org_id, name, created_by)
  select '10000000-0000-0000-0000-000000000002', org, 'second site', auth.uid() from ctx;
select pg_temp.ok((select count(*) = 2 from project_settings), 'settings row per project');
select pg_temp.ok((select role = 'manager' from project_members where user_id = auth.uid() limit 1), 'creator is manager');
select pg_temp.ok((select shift_hours = 8 from project_settings limit 1), 'default 8 hour shift');

do $$ begin
  insert into projects (org_id, name, start_date, end_date, created_by)
    select org, 'bad dates', '2026-10-12', '2026-10-01', auth.uid() from ctx;
  raise exception 'FAIL: bad project dates accepted';
exception when check_violation then null; end $$;

-- numbering
insert into tasks (project_id, name) values ('10000000-0000-0000-0000-000000000001', 'brick work'),
                                            ('10000000-0000-0000-0000-000000000001', 'plaster');
select pg_temp.ok((select array_agg(number order by number) = '{1,2}' from tasks), 'task numbers 1,2');
insert into issues (project_id, title) values ('10000000-0000-0000-0000-000000000001', 'no water on site');
update issues set status = 'closed', closed_at = now();
select pg_temp.ok((select array_agg(verb order by verb) from activity_events
                   where project_id = '10000000-0000-0000-0000-000000000001')
                  = '{closed_issue,created_project,created_task,created_task,joined,raised_issue}', 'feed events');
do $$ begin
  insert into activity_events (project_id, verb, entity_type) values ('10000000-0000-0000-0000-000000000001', 'fake', 'x');
  raise exception 'FAIL: client forged a feed event';
exception when insufficient_privilege then null; end $$;

-- invite B as engineer on palak
insert into invites (org_id, project_id, phone, project_role)
  select org, '10000000-0000-0000-0000-000000000001', '+910000000002', 'engineer' from ctx;

-- ---------------------------------------------------------------- stock ledger
insert into materials (id, org_id, name, spec, uom) select '20000000-0000-0000-0000-000000000001', org, 'cement', 'acc', 'pk' from ctx;
insert into subcontractors (id, org_id, name) select '30000000-0000-0000-0000-000000000001', org, 'Ravi Masonry' from ctx;

create function pg_temp.stock(p uuid, s uuid) returns numeric language sql as
$$ select coalesce(sum(qty), 0) from stock_movements
   where project_id = p and material_id = '20000000-0000-0000-0000-000000000001' and subcontractor_id is not distinct from s $$;

insert into grns (id, project_id, received_on) values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '2026-10-12');
insert into grn_lines (id, grn_id, material_id, qty) values
  ('41000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 10),
  ('41000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 5);
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', null) = 15, 'GRN adds 15');

insert into material_issues (id, project_id, subcontractor_id, issued_on)
  values ('50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '2026-10-12');
insert into material_issue_lines (material_issue_id, material_id, qty)
  values ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 4);
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', null) = 11, 'issue to subcontractor leaves project');
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001') = 4, '... and lands with subcontractor');

insert into material_returns (id, project_id, subcontractor_id, returned_on)
  values ('51000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '2026-10-13');
insert into material_return_lines (material_return_id, material_id, qty)
  values ('51000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 1);
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', null) = 12, 'return comes back');
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001') = 3, 'subcontractor holds 3');

-- correcting a GRN line moves stock and is audited
update grn_lines set qty = 20 where id = '41000000-0000-0000-0000-000000000001';
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', null) = 22, 'GRN correction 10 -> 20');
-- deleting an issue gives the stock back
delete from material_issues where id = '50000000-0000-0000-0000-000000000001';
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', null) = 26, 'deleted issue restores project stock');
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001') = -1,
                  'subcontractor goes to -1 (returned more than now issued): flagged, not blocked');

-- transfer: leaves on send, arrives on receipt
insert into transfer_orders (id, project_id, to_project_id, sent_on)
  values ('60000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', '2026-10-13');
insert into transfer_order_lines (transfer_order_id, material_id, qty)
  values ('60000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 5);
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000001', null) = 21, 'transfer leaves sender');
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000002', null) = 0, 'nothing arrives while in transit');
update transfer_orders set status = 'received', received_on = '2026-10-14' where id = '60000000-0000-0000-0000-000000000001';
select pg_temp.ok(pg_temp.stock('10000000-0000-0000-0000-000000000002', null) = 5, 'arrives on receipt');
select pg_temp.ok((select in_stock = 21 from stock_balances
                   where project_id = '10000000-0000-0000-0000-000000000001' and subcontractor_id is null), 'stock_balances view agrees');

-- ---------------------------------------------------------------- attendance and payables
insert into workers (id, org_id, project_id, name, wage_type, wage_paise, ot_rate_paise)
  select '70000000-0000-0000-0000-000000000001', org, '10000000-0000-0000-0000-000000000001', 'Suresh', 'daily', 80000, 10000 from ctx;
insert into attendance (project_id, worker_id, work_date, status, ot_hours) values
  ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-05', 'present', 2),
  ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-06', 'present', 0),
  ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-07', 'present', 0),
  ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-08', 'half_day', 0),
  ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-09', 'absent', 0);
-- a second phone marking the same worker and day merges instead of duplicating
insert into attendance (project_id, worker_id, work_date, status, ot_hours)
  values ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-09', 'absent', 0)
  on conflict (project_id, worker_id, work_date) do update set status = excluded.status;
select pg_temp.ok((select count(*) = 5 from attendance), 'one record per worker per day');

-- 3.5 days x Rs 800 + 2 OT hours x Rs 100 = Rs 3,000
select pg_temp.ok((select amount_paise = 300000 and days = 3.5
                   from preview_attendance_payables('10000000-0000-0000-0000-000000000001', '2026-10-01', '2026-10-31')),
                  'wages preview Rs 3000');
insert into payables (project_id, kind, worker_id, period_start, period_end, amount_paise)
  values ('10000000-0000-0000-0000-000000000001', 'attendance', '70000000-0000-0000-0000-000000000001', '2026-10-01', '2026-10-31', 300000)
  returning number \gset pay_
select pg_temp.ok((select count(*) = 0
                   from preview_attendance_payables('10000000-0000-0000-0000-000000000001', '2026-10-01', '2026-10-31')),
                  'recorded days are not offered again');
insert into payments (project_id, payable_id, amount_paise, paid_on)
  select project_id, id, 100000, '2026-10-14' from payables where kind = 'attendance';
select pg_temp.ok((select status = 'partly_paid' from payables where kind = 'attendance'), 'partly paid');
insert into payments (project_id, payable_id, amount_paise, paid_on)
  select project_id, id, 200000, '2026-10-15' from payables where kind = 'attendance';
select pg_temp.ok((select status = 'paid' from payables where kind = 'attendance'), 'paid');

insert into expenses (project_id, category, amount_paise, spent_on)
  values ('10000000-0000-0000-0000-000000000001', 'petty_spend', 25000, '2026-10-14');
select pg_temp.ok((select sum(amount_paise) = 325000 from expense_lines
                   where project_id = '10000000-0000-0000-0000-000000000001'), 'expenses total Rs 3250');

-- corrections leave a trail and a tombstone
delete from attendance where work_date = '2026-10-09';
select pg_temp.ok((select count(*) = 1 from audit_log where table_name = 'attendance' and action = 'delete'), 'attendance delete audited');
select pg_temp.ok((select count(*) = 1 from tombstones where table_name = 'attendance'), 'attendance delete tombstoned');

-- ---------------------------------------------------------------- B joins as engineer
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
select pg_temp.ok(public.accept_invites() = 1, 'B accepts invite');
select pg_temp.ok((select count(*) = 1 from projects), 'B sees only palak');
select pg_temp.ok((select count(*) = 2 from tasks), 'B sees tasks');
select pg_temp.ok((select count(*) = 0 from payables), 'B cannot see money');
select pg_temp.ok((select count(*) > 0 from stock_movements), 'B sees stock');
insert into attendance (project_id, worker_id, work_date, status)
  values ('10000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001', '2026-10-10', 'present');
do $$ begin
  insert into payables (project_id, kind, title, amount_paise) values ('10000000-0000-0000-0000-000000000001', 'custom', 'x', 1);
  raise exception 'FAIL: engineer recorded a payable';
exception when insufficient_privilege then null; end $$;
do $$ begin
  insert into stock_movements (project_id, material_id, qty, occurred_on, source_table, source_id)
    values ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 100, '2026-10-14', 'x', gen_random_uuid());
  raise exception 'FAIL: client wrote stock_movements';
exception when insufficient_privilege then null; end $$;

-- ---------------------------------------------------------------- C sees nothing
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
select pg_temp.ok((select count(*) = 0 from projects), 'outsider sees no projects');
select pg_temp.ok((select count(*) = 0 from tasks), 'outsider sees no tasks');
select pg_temp.ok((select count(*) = 0 from profiles where id <> auth.uid()), 'outsider sees no other people');

reset role;
select 'smoke: ok';
