-- Demo data for local development and screenshots (`supabase db reset` runs it). Never run on production.
-- Every person here is invented; phone numbers use the 98000-0000x range.

insert into auth.users (id, phone) values
  ('5eed0000-0000-0000-0000-000000000001', '919800000001'),
  ('5eed0000-0000-0000-0000-000000000002', '919800000002'),
  ('5eed0000-0000-0000-0000-000000000003', '919800000003');
update profiles set name = 'Anita Rao' where id = '5eed0000-0000-0000-0000-000000000001';
update profiles set name = 'Ravi Singh' where id = '5eed0000-0000-0000-0000-000000000002';
update profiles set name = 'Meena Iyer' where id = '5eed0000-0000-0000-0000-000000000003';

insert into organisations (id, name) values ('5eed0000-0000-0000-0001-000000000001', 'Sharma Constructions');
insert into org_members (org_id, user_id, role) values
  ('5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0000-000000000001', 'owner'),
  ('5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0000-000000000002', 'member'),
  ('5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0000-000000000003', 'member');

insert into projects (id, org_id, name, address, start_date, end_date, created_by) values
  ('5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0001-000000000001', 'Green Park Tower B', 'Sector 21, Gurugram',
   date_trunc('month', current_date - interval '2 months')::date, current_date + 180, '5eed0000-0000-0000-0000-000000000001'),
  ('5eed0000-0000-0000-0002-000000000002', '5eed0000-0000-0000-0001-000000000001', 'Lakeview Villas', 'Whitefield, Bengaluru',
   current_date - 20, null, '5eed0000-0000-0000-0000-000000000001');
insert into project_members (project_id, user_id, role) values
  ('5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000002', 'engineer'),
  ('5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000003', 'supervisor');

insert into task_categories (org_id, name)
  select '5eed0000-0000-0000-0001-000000000001', c from unnest(array['Civil', 'Masonry', 'Finishing', 'MEP']) c;

insert into tasks (id, project_id, name, unit, planned_qty, start_date, due_date, assignee_id) values
  ('5eed0000-0000-0000-0003-000000000001', '5eed0000-0000-0000-0002-000000000001', 'Excavation and PCC', 'm3', 420, current_date - 55, current_date - 30, '5eed0000-0000-0000-0000-000000000002'),
  ('5eed0000-0000-0000-0003-000000000002', '5eed0000-0000-0000-0002-000000000001', 'Brickwork level 3', 'm2', 600, current_date - 12, current_date + 10, '5eed0000-0000-0000-0000-000000000002'),
  ('5eed0000-0000-0000-0003-000000000003', '5eed0000-0000-0000-0002-000000000001', 'Plaster level 2', 'm2', 900, current_date - 20, current_date - 2, '5eed0000-0000-0000-0000-000000000003'),
  ('5eed0000-0000-0000-0003-000000000004', '5eed0000-0000-0000-0002-000000000001', 'Electrical conduits level 3', null, null, current_date + 5, current_date + 25, null);

insert into task_updates (task_id, project_id, author_id, work_date, qty_done, remarks) values
  ('5eed0000-0000-0000-0003-000000000001', '5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000002', current_date - 40, 420, 'PCC done for all footings.'),
  ('5eed0000-0000-0000-0003-000000000002', '5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000002', current_date - 6, 120, 'East wall up to sill level.'),
  ('5eed0000-0000-0000-0003-000000000002', '5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000002', current_date - 3, 95, null),
  ('5eed0000-0000-0000-0003-000000000002', '5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000002', current_date - 1, 110, 'Waiting on bricks for the north side.'),
  ('5eed0000-0000-0000-0003-000000000003', '5eed0000-0000-0000-0002-000000000001', '5eed0000-0000-0000-0000-000000000003', current_date - 4, 300, 'Internal plaster, flats 201-204.');

insert into workers (id, org_id, project_id, name, skill, wage_paise, ot_rate_paise) values
  ('5eed0000-0000-0000-0004-000000000001', '5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0002-000000000001', 'Suresh Kumar', 'Mason', 80000, 10000),
  ('5eed0000-0000-0000-0004-000000000002', '5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0002-000000000001', 'Imran Khan', 'Mason', 80000, 10000),
  ('5eed0000-0000-0000-0004-000000000003', '5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0002-000000000001', 'Lakshmi Devi', 'Helper', 55000, 7000),
  ('5eed0000-0000-0000-0004-000000000004', '5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0002-000000000001', 'Gopal Yadav', 'Helper', 55000, 7000),
  ('5eed0000-0000-0000-0004-000000000005', '5eed0000-0000-0000-0001-000000000001', '5eed0000-0000-0000-0002-000000000001', 'Arjun Patil', 'Carpenter', 90000, 12000);

-- last six working days: mostly present, a few half days and absences
insert into attendance (project_id, worker_id, work_date, status, ot_hours)
select '5eed0000-0000-0000-0002-000000000001', w.id, d::date,
       case (w.n + extract(day from d)::int) % 7 when 0 then 'absent' when 3 then 'half_day' else 'present' end,
       case when w.n < 2 and (w.n + extract(day from d)::int) % 7 not in (0, 3) and extract(day from d)::int % 2 = 0 then 2 else 0 end
from generate_series(current_date - 6, current_date - 1, interval '1 day') d
cross join (select id, row_number() over (order by name) - 1 as n
            from workers where project_id = '5eed0000-0000-0000-0002-000000000001') w
where extract(isodow from d) <> 7;
