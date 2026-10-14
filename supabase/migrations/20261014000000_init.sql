-- Site app: initial schema (Supabase / Postgres 15+).
-- Conventions
--   * ids are uuid. Clients may generate them (offline-first), so every insert is an idempotent upsert by id.
--   * times are timestamptz (UTC). Calendar days on site are `date` (the project's local day).
--   * money is integer paise + currency ('INR').
--   * quantities are numeric(14,3) (bags, kg, m3, nos, hours).
--   * hard deletes everywhere; a trigger writes `tombstones` so offline clients can sync deletions,
--     and `audit_log` keeps old rows for the tables where users must be able to correct mistakes.
--   * access: Postgres row level security. Helper functions live in schema `app`.

create extension if not exists pgcrypto;
create schema if not exists app;

-- ---------------------------------------------------------------- shared trigger functions

create or replace function app.set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------- tenancy and people

create table organisations (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(trim(name)) between 1 and 120),
  logo_path text,
  plan text not null default 'free' check (plan in ('free','pro','pro_plus','advanced')),
  default_language text not null default 'en',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  name text not null default '',
  phone text unique,
  email text,
  language text not null default 'en',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table org_members (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  user_id uuid not null references profiles(id) on delete cascade,
  -- owner/admin = "Organization Management Team": sees every project and all money.
  role text not null default 'member' check (role in ('owner','admin','member')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (org_id, user_id)
);
create index on org_members (user_id);

create table projects (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  name text not null check (length(trim(name)) between 1 and 120),
  image_path text,
  address text,
  status text not null default 'active' check (status in ('active','on_hold','completed','archived')),
  start_date date,
  end_date date,
  timezone text not null default 'Asia/Kolkata',
  progress_pct numeric(5,2) not null default 0 check (progress_pct between 0 and 100),
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- the original shows a raw backend error when dates are bad (recon S21); refuse them at the source.
  constraint project_dates check (end_date is null or start_date is null or end_date >= start_date)
);
create index on projects (org_id, status);

create table project_members (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  user_id uuid not null references profiles(id) on delete cascade,
  role text not null default 'engineer'
    check (role in ('manager','engineer','supervisor','storekeeper','accountant','viewer')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, user_id)
);
create index on project_members (user_id);

create table invites (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  project_id uuid references projects(id) on delete cascade,
  phone text not null,
  org_role text not null default 'member' check (org_role in ('admin','member')),
  project_role text check (project_role in ('manager','engineer','supervisor','storekeeper','accountant','viewer')),
  invited_by uuid references profiles(id) on delete set null,
  accepted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on invites (phone) where accepted_at is null;
create index on invites (org_id);

create table project_settings (
  project_id uuid primary key references projects(id) on delete cascade,
  attendance_remarks boolean not null default true,      -- S24 "Work Updates"
  shift_hours numeric(4,2) not null default 8 check (shift_hours > 0 and shift_hours <= 24),
  hourly_attendance boolean not null default false,       -- S24 "Labour hourly shift"
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Human-friendly per-project numbers (task #12, GRN #4). Assigned on the server when a row arrives,
-- so offline-created rows show "pending" until they sync.
create table doc_counters (
  project_id uuid not null references projects(id) on delete cascade,
  kind text not null,
  next_value integer not null default 1,
  primary key (project_id, kind)
);

create or replace function app.next_doc_number(p_project uuid, p_kind text) returns integer
language plpgsql security definer set search_path = public as $$
declare v integer;
begin
  insert into doc_counters (project_id, kind, next_value) values (p_project, p_kind, 2)
  on conflict (project_id, kind) do update set next_value = doc_counters.next_value + 1
  returning next_value - 1 into v;
  return v;
end $$;

create or replace function app.assign_number() returns trigger
language plpgsql as $$
begin
  if new.number is null then
    new.number := app.next_doc_number(new.project_id, tg_table_name);
  end if;
  return new;
end $$;

-- ---------------------------------------------------------------- tasks, issues, feed, media

create table task_categories (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  name text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (org_id, name)
);

create table subcontractors (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  name text not null,
  phone text,
  trade text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on subcontractors (org_id);

create table work_orders (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  subcontractor_id uuid not null references subcontractors(id) on delete restrict,
  number integer,
  title text not null,
  status text not null default 'draft' check (status in ('draft','issued','completed','cancelled')),
  retention_pct numeric(5,2) not null default 0 check (retention_pct between 0 and 100),
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number)
);
create index on work_orders (subcontractor_id);
create index on work_orders (project_id, updated_at);

create table work_order_lines (
  id uuid primary key default gen_random_uuid(),
  work_order_id uuid not null references work_orders(id) on delete cascade,
  item text not null,
  unit text not null,
  qty numeric(14,3) not null check (qty > 0),
  rate_paise bigint not null check (rate_paise >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on work_order_lines (work_order_id);

create table tasks (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,                                   -- S08 "Search by id"
  parent_id uuid references tasks(id) on delete cascade,
  name text not null check (length(trim(name)) between 1 and 200),
  category_id uuid references task_categories(id) on delete set null,
  unit text,
  planned_qty numeric(14,3) check (planned_qty is null or planned_qty > 0),
  done_qty numeric(14,3) not null default 0 check (done_qty >= 0),
  progress_pct numeric(5,2) not null default 0 check (progress_pct between 0 and 100),
  start_date date,
  due_date date,
  assignee_id uuid references profiles(id) on delete set null,
  work_order_id uuid references work_orders(id) on delete set null,
  status text not null default 'not_started'
    check (status in ('not_started','in_progress','blocked','completed')),
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number),
  constraint task_dates check (due_date is null or start_date is null or due_date >= start_date)
);
create index on tasks (project_id, status);
create index on tasks (project_id, due_date);
create index on tasks (project_id, updated_at);
create index on tasks (assignee_id);
create index on tasks (parent_id);
create index on tasks (category_id);
create index on tasks (work_order_id);

create table task_updates (
  id uuid primary key default gen_random_uuid(),
  task_id uuid not null references tasks(id) on delete cascade,
  project_id uuid not null references projects(id) on delete cascade,
  author_id uuid references profiles(id) on delete set null,
  work_date date not null,
  qty_done numeric(14,3) check (qty_done is null or qty_done >= 0),
  progress_pct numeric(5,2) check (progress_pct is null or progress_pct between 0 and 100),
  remarks text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on task_updates (task_id, work_date);
create index on task_updates (project_id, work_date);
create index on task_updates (project_id, updated_at);

create table issues (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  task_id uuid references tasks(id) on delete set null,
  title text not null,
  description text,
  due_date date,
  assignee_id uuid references profiles(id) on delete set null,
  status text not null default 'open' check (status in ('open','closed')),
  raised_by uuid references profiles(id) on delete set null,
  closed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number),
  constraint closed_has_time check ((status = 'closed') = (closed_at is not null))
);
create index on issues (project_id, status);
create index on issues (project_id, updated_at);
create index on issues (task_id);
create index on issues (assignee_id);

create table comments (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  author_id uuid references profiles(id) on delete set null,
  task_id uuid references tasks(id) on delete cascade,
  issue_id uuid references issues(id) on delete cascade,
  body text not null check (length(body) between 1 and 4000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint one_parent check (num_nonnulls(task_id, issue_id) = 1)
);
create index on comments (task_id);
create index on comments (issue_id);
create index on comments (project_id, updated_at);

create table activity_events (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  actor_id uuid references profiles(id) on delete set null,
  verb text not null,                 -- created_task, raised_issue, updated_progress, joined, ...
  entity_type text not null,
  entity_id uuid,
  summary text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on activity_events (project_id, created_at desc);
create index on activity_events (project_id, updated_at);

create table albums (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  name text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, name)
);

create table vendors (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  name text not null,
  phone text,
  gstin text check (gstin is null or gstin ~ '^[0-9A-Z]{15}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on vendors (org_id);

create table materials (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  name text not null,                 -- "cement"
  spec text not null default '',      -- brand / grade, e.g. "acc"
  uom text not null,                  -- "pk", "kg", "m3", "nos"
  category text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (org_id, name, spec, uom)
);

-- ---------------------------------------------------------------- procurement

create table indents (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  requested_by uuid references profiles(id) on delete set null,
  needed_by date,
  status text not null default 'submitted'
    check (status in ('draft','submitted','approved','rejected','ordered','closed')),
  decided_by uuid references profiles(id) on delete set null,
  decided_at timestamptz,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number)
);
create index on indents (project_id, status);
create index on indents (project_id, updated_at);

create table indent_lines (
  id uuid primary key default gen_random_uuid(),
  indent_id uuid not null references indents(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on indent_lines (indent_id);
create index on indent_lines (material_id);

create table purchase_orders (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  vendor_id uuid not null references vendors(id) on delete restrict,
  indent_id uuid references indents(id) on delete set null,
  expected_on date,
  status text not null default 'draft'
    check (status in ('draft','sent','partly_received','received','cancelled')),
  currency text not null default 'INR',
  notes text,
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number)
);
create index on purchase_orders (project_id, status);
create index on purchase_orders (project_id, expected_on);
create index on purchase_orders (project_id, updated_at);
create index on purchase_orders (vendor_id);
create index on purchase_orders (indent_id);

create table po_lines (
  id uuid primary key default gen_random_uuid(),
  po_id uuid not null references purchase_orders(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty > 0),
  rate_paise bigint not null check (rate_paise >= 0),
  tax_pct numeric(5,2) not null default 0 check (tax_pct between 0 and 100),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on po_lines (po_id);
create index on po_lines (material_id);

create table grns (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  po_id uuid references purchase_orders(id) on delete set null,   -- null = "Site GRN" without a PO
  vendor_id uuid references vendors(id) on delete set null,
  received_on date not null,
  challan_no text,
  notes text,
  received_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number)
);
create index on grns (project_id, received_on);
create index on grns (project_id, updated_at);
create index on grns (po_id);
create index on grns (vendor_id);

create table grn_lines (
  id uuid primary key default gen_random_uuid(),
  grn_id uuid not null references grns(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  po_line_id uuid references po_lines(id) on delete set null,
  qty numeric(14,3) not null check (qty > 0),
  rate_paise bigint check (rate_paise is null or rate_paise >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on grn_lines (grn_id);
create index on grn_lines (material_id);
create index on grn_lines (po_line_id);

-- ---------------------------------------------------------------- inventory

create table transfer_requests (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,          -- the project asking
  source_project_id uuid not null references projects(id) on delete cascade,
  number integer,
  status text not null default 'requested'
    check (status in ('requested','approved','rejected','fulfilled','cancelled')),
  requested_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number),
  constraint different_projects check (project_id <> source_project_id)
);
create index on transfer_requests (source_project_id, status);
create index on transfer_requests (project_id, updated_at);

create table transfer_request_lines (
  id uuid primary key default gen_random_uuid(),
  transfer_request_id uuid not null references transfer_requests(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on transfer_request_lines (transfer_request_id);
create index on transfer_request_lines (material_id);

create table transfer_orders (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,          -- the project sending
  to_project_id uuid not null references projects(id) on delete cascade,
  transfer_request_id uuid references transfer_requests(id) on delete set null,
  number integer,
  sent_on date not null,
  received_on date,
  status text not null default 'in_transit' check (status in ('in_transit','received','cancelled')),
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number),
  constraint different_projects check (project_id <> to_project_id),
  constraint received_has_date check ((status = 'received') = (received_on is not null))
);
create index on transfer_orders (to_project_id, status);
create index on transfer_orders (project_id, updated_at);
create index on transfer_orders (transfer_request_id);

create table transfer_order_lines (
  id uuid primary key default gen_random_uuid(),
  transfer_order_id uuid not null references transfer_orders(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on transfer_order_lines (transfer_order_id);
create index on transfer_order_lines (material_id);

create table material_issues (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  task_id uuid references tasks(id) on delete set null,
  subcontractor_id uuid references subcontractors(id) on delete restrict,
  issued_on date not null,
  issued_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number),
  constraint issued_to check (num_nonnulls(task_id, subcontractor_id) >= 1)
);
create index on material_issues (project_id, issued_on);
create index on material_issues (project_id, updated_at);
create index on material_issues (task_id);
create index on material_issues (subcontractor_id);

create table material_issue_lines (
  id uuid primary key default gen_random_uuid(),
  material_issue_id uuid not null references material_issues(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on material_issue_lines (material_issue_id);
create index on material_issue_lines (material_id);

create table material_returns (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  task_id uuid references tasks(id) on delete set null,
  subcontractor_id uuid references subcontractors(id) on delete restrict,
  returned_on date not null,
  received_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number)
);
create index on material_returns (project_id, returned_on);
create index on material_returns (project_id, updated_at);
create index on material_returns (subcontractor_id);
create index on material_returns (task_id);

create table material_return_lines (
  id uuid primary key default gen_random_uuid(),
  material_return_id uuid not null references material_returns(id) on delete cascade,
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on material_return_lines (material_return_id);
create index on material_return_lines (material_id);

-- S15 "+ Update": opening stock, consumption without an issue, count corrections.
create table stock_adjustments (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  subcontractor_id uuid references subcontractors(id) on delete restrict,  -- null = project inventory
  material_id uuid not null references materials(id) on delete restrict,
  kind text not null check (kind in ('opening','consumption','correction')),
  qty numeric(14,3) not null check (qty <> 0),   -- signed: + adds stock, - removes it
  task_id uuid references tasks(id) on delete set null,
  adjusted_on date not null,
  note text,
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint consumption_removes check (kind <> 'consumption' or qty < 0)
);
create index on stock_adjustments (project_id, adjusted_on);
create index on stock_adjustments (project_id, updated_at);
create index on stock_adjustments (material_id);
create index on stock_adjustments (subcontractor_id);
create index on stock_adjustments (task_id);

-- The single source of truth for stock. Written only by triggers on the document tables above;
-- clients read it, never write it. Stock = sum(qty) per (project, owner, material).
create table stock_movements (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  subcontractor_id uuid references subcontractors(id) on delete cascade,   -- null = project inventory
  material_id uuid not null references materials(id) on delete restrict,
  qty numeric(14,3) not null check (qty <> 0),
  occurred_on date not null,
  source_table text not null,
  source_id uuid not null,
  leg smallint not null default 1,      -- a transfer or issue to a subcontractor has two legs
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (source_table, source_id, leg)
);
create index on stock_movements (project_id, material_id, subcontractor_id);
create index on stock_movements (project_id, occurred_on);
create index on stock_movements (project_id, updated_at);
create index on stock_movements (material_id);
create index on stock_movements (subcontractor_id);

create or replace view stock_balances with (security_invoker = true) as
select project_id, subcontractor_id, material_id, sum(qty) as in_stock, max(occurred_on) as last_movement_on
from stock_movements
group by project_id, subcontractor_id, material_id;

-- ---------------------------------------------------------------- labour and money

create table workers (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  project_id uuid not null references projects(id) on delete cascade,    -- current site
  subcontractor_id uuid references subcontractors(id) on delete set null, -- labour contractor, if any
  name text not null,
  skill text,                                                              -- mason, helper, carpenter ...
  phone text,
  wage_type text not null default 'daily' check (wage_type in ('daily','hourly')),
  wage_paise bigint not null default 0 check (wage_paise >= 0),
  ot_rate_paise bigint not null default 0 check (ot_rate_paise >= 0),     -- per hour
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on workers (project_id, active);
create index on workers (project_id, updated_at);
create index on workers (org_id);
create index on workers (subcontractor_id);

create table attendance (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  worker_id uuid not null references workers(id) on delete cascade,
  work_date date not null,
  status text not null check (status in ('present','absent','half_day','hours')),
  hours numeric(4,2) check (hours is null or (hours > 0 and hours <= 24)),
  ot_hours numeric(4,2) not null default 0 check (ot_hours >= 0 and ot_hours <= 16),
  remarks text,
  marked_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- one record per worker per day; two phones marking offline merge on this key (upsert on conflict).
  unique (project_id, worker_id, work_date),
  constraint hours_when_hourly check ((status = 'hours') = (hours is not null))
);
create index on attendance (project_id, work_date);
create index on attendance (project_id, updated_at);
create index on attendance (worker_id, work_date);

create table payables (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  number integer,
  kind text not null check (kind in ('attendance','purchase_order','grn','work_order','custom')),
  worker_id uuid references workers(id) on delete restrict,
  subcontractor_id uuid references subcontractors(id) on delete restrict,
  vendor_id uuid references vendors(id) on delete restrict,
  po_id uuid references purchase_orders(id) on delete restrict,
  grn_id uuid references grns(id) on delete restrict,
  work_order_id uuid references work_orders(id) on delete restrict,
  title text,
  period_start date,
  period_end date,
  amount_paise bigint not null check (amount_paise >= 0),
  currency text not null default 'INR',
  status text not null default 'due' check (status in ('due','partly_paid','paid','void')),
  recorded_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, number),
  constraint period_order check (period_end is null or period_start is null or period_end >= period_start),
  constraint kind_source check (
    (kind = 'attendance'     and num_nonnulls(worker_id, subcontractor_id) >= 1 and period_start is not null and period_end is not null) or
    (kind = 'purchase_order' and po_id is not null) or
    (kind = 'grn'            and grn_id is not null) or
    (kind = 'work_order'     and work_order_id is not null) or
    (kind = 'custom'         and title is not null)
  )
);
create index on payables (project_id, status);
create index on payables (project_id, updated_at);
create index on payables (project_id, period_start, period_end);
create index on payables (worker_id);
create index on payables (subcontractor_id);
create index on payables (vendor_id);
create index on payables (po_id);
create index on payables (grn_id);
create index on payables (work_order_id);
-- a purchase is paid once: one live payable per PO and per GRN.
create unique index payables_one_per_po on payables (po_id) where po_id is not null and status <> 'void';
create unique index payables_one_per_grn on payables (grn_id) where grn_id is not null and status <> 'void';

create table payments (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  payable_id uuid not null references payables(id) on delete cascade,
  amount_paise bigint not null check (amount_paise > 0),
  paid_on date not null,
  mode text not null default 'cash' check (mode in ('cash','upi','bank','cheque','other')),
  reference text,
  recorded_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on payments (payable_id);
create index on payments (project_id, paid_on);
create index on payments (project_id, updated_at);

-- Manually entered spend. Material, subcontractor and attendance spend come from payables (see view).
create table expenses (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  category text not null check (category in ('petty_spend','miscellaneous','assets')),
  amount_paise bigint not null check (amount_paise > 0),
  currency text not null default 'INR',
  spent_on date not null,
  note text,
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on expenses (project_id, spent_on);
create index on expenses (project_id, updated_at);

-- S26: six buckets, each rupee counted once.
create or replace view expense_lines with (security_invoker = true) as
select p.project_id, p.id as source_id, 'payable'::text as source_table,
       case p.kind when 'purchase_order' then 'material'
                   when 'grn' then 'material'
                   when 'work_order' then 'subcontractor'
                   when 'attendance' then 'attendance'
                   else 'miscellaneous' end as category,
       p.amount_paise, coalesce(p.period_end, p.created_at::date) as spent_on
from payables p where p.status <> 'void'
union all
select e.project_id, e.id, 'expense', e.category, e.amount_paise, e.spent_on from expenses e;

-- ---------------------------------------------------------------- media, files, reports, chat

create table media (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  album_id uuid references albums(id) on delete set null,
  kind text not null check (kind in ('photo','video')),
  storage_path text not null unique,       -- bucket "project-media/<project_id>/<id>.<ext>"
  bytes bigint check (bytes is null or bytes between 1 and 104857600),
  taken_at timestamptz not null,
  lat numeric(9,6),
  lng numeric(9,6),
  uploaded_by uuid references profiles(id) on delete set null,
  task_update_id uuid references task_updates(id) on delete set null,
  issue_id uuid references issues(id) on delete set null,
  grn_id uuid references grns(id) on delete set null,
  expense_id uuid references expenses(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on media (project_id, taken_at desc);
create index on media (project_id, updated_at);
create index on media (album_id);
create index on media (task_update_id);
create index on media (issue_id);
create index on media (grn_id);
create index on media (expense_id);

create table drive_files (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  folder text not null default '/',
  name text not null,
  storage_path text not null unique,       -- bucket "project-drive"
  mime text,
  bytes bigint check (bytes is null or bytes between 1 and 104857600),
  uploaded_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (project_id, folder, name)
);
create index on drive_files (project_id, updated_at);

create table reports (
  id uuid primary key default gen_random_uuid(),
  project_id uuid not null references projects(id) on delete cascade,
  type text not null check (type in ('detailed','summary')),
  from_date date not null,
  to_date date not null,
  parts jsonb not null,                    -- {"task_updates":true,"material":{"grn":true,...},...}
  language text not null default 'en',
  status text not null default 'queued' check (status in ('queued','rendering','ready','failed')),
  storage_path text,
  error text,
  expires_at timestamptz not null default now() + interval '90 days',   -- the original keeps 15
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint report_range check (to_date >= from_date and to_date - from_date <= 366)
);
create index on reports (project_id, created_at desc);
create index on reports (status) where status in ('queued','rendering');
create index on reports (expires_at);

create table chat_messages (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organisations(id) on delete cascade,
  project_id uuid references projects(id) on delete cascade,     -- null = org-wide channel
  author_id uuid references profiles(id) on delete set null,
  body text not null check (length(body) between 1 and 4000),
  media_id uuid references media(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on chat_messages (org_id, project_id, created_at desc);

create table notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references profiles(id) on delete cascade,
  project_id uuid references projects(id) on delete cascade,
  kind text not null,
  payload jsonb not null default '{}',
  read_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on notifications (user_id, created_at desc);
create index on notifications (user_id) where read_at is null;

create table devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references profiles(id) on delete cascade,
  fcm_token text not null unique,
  platform text not null check (platform in ('android','ios','web')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on devices (user_id);

create table subscriptions (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null unique references organisations(id) on delete cascade,
  plan text not null check (plan in ('pro','pro_plus','advanced')),
  provider text not null default 'razorpay',
  provider_subscription_id text not null unique,
  status text not null check (status in ('created','active','past_due','halted','cancelled','completed')),
  current_period_end timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table webhook_events (
  id text primary key,                     -- provider event id: replays are ignored
  provider text not null,
  payload jsonb not null,
  processed_at timestamptz,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------- sync and audit

create table tombstones (
  id bigint generated always as identity primary key,
  table_name text not null,
  row_id uuid not null,
  project_id uuid,
  org_id uuid,
  deleted_at timestamptz not null default now()
);
create index on tombstones (project_id, deleted_at);
create index on tombstones (org_id, deleted_at);

create table audit_log (
  id bigint generated always as identity primary key,
  table_name text not null,
  row_id uuid not null,
  project_id uuid,
  action text not null check (action in ('update','delete')),
  old_row jsonb not null,
  actor_id uuid,
  at timestamptz not null default now()
);
create index on audit_log (table_name, row_id);
create index on audit_log (project_id, at desc);

create or replace function app.write_tombstone() returns trigger
language plpgsql security definer set search_path = public as $$
declare j jsonb := to_jsonb(old);
begin
  insert into tombstones (table_name, row_id, project_id, org_id)
  values (tg_table_name, old.id, (j->>'project_id')::uuid, (j->>'org_id')::uuid);
  return old;
end $$;

create or replace function app.write_audit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into audit_log (table_name, row_id, project_id, action, old_row, actor_id)
  values (tg_table_name, old.id, (to_jsonb(old)->>'project_id')::uuid, lower(tg_op), to_jsonb(old), auth.uid());
  return coalesce(new, old);
end $$;

-- ---------------------------------------------------------------- stock movement triggers

create or replace function app.put_movement(
  p_table text, p_id uuid, p_leg smallint, p_project uuid, p_sub uuid,
  p_material uuid, p_qty numeric, p_on date) returns void
language plpgsql security definer set search_path = public as $$
begin
  delete from stock_movements where source_table = p_table and source_id = p_id and leg = p_leg;
  if p_qty is not null and p_qty <> 0 then
    insert into stock_movements (project_id, subcontractor_id, material_id, qty, occurred_on, source_table, source_id, leg)
    values (p_project, p_sub, p_material, p_qty, p_on, p_table, p_id, p_leg);
  end if;
end $$;

create or replace function app.drop_movements(p_table text, p_id uuid) returns void
language sql security definer set search_path = public as $$
  delete from stock_movements where source_table = p_table and source_id = p_id;
$$;

create or replace function app.grn_line_movement() returns trigger
language plpgsql security definer set search_path = public as $$
declare h grns;
begin
  if tg_op = 'DELETE' then perform app.drop_movements('grn_lines', old.id); return old; end if;
  select * into h from grns where id = new.grn_id;
  perform app.put_movement('grn_lines', new.id, 1::smallint, h.project_id, null, new.material_id, new.qty, h.received_on);
  return new;
end $$;

create or replace function app.issue_line_movement() returns trigger
language plpgsql security definer set search_path = public as $$
declare h material_issues;
begin
  if tg_op = 'DELETE' then perform app.drop_movements('material_issue_lines', old.id); return old; end if;
  select * into h from material_issues where id = new.material_issue_id;
  -- leaves project stock ...
  perform app.put_movement('material_issue_lines', new.id, 1::smallint, h.project_id, null, new.material_id, -new.qty, h.issued_on);
  -- ... and lands in the subcontractor's inventory when issued to one (an issue to a task is consumed).
  perform app.put_movement('material_issue_lines', new.id, 2::smallint, h.project_id, h.subcontractor_id, new.material_id,
                           case when h.subcontractor_id is null then null else new.qty end, h.issued_on);
  return new;
end $$;

create or replace function app.return_line_movement() returns trigger
language plpgsql security definer set search_path = public as $$
declare h material_returns;
begin
  if tg_op = 'DELETE' then perform app.drop_movements('material_return_lines', old.id); return old; end if;
  select * into h from material_returns where id = new.material_return_id;
  perform app.put_movement('material_return_lines', new.id, 1::smallint, h.project_id, null, new.material_id, new.qty, h.returned_on);
  perform app.put_movement('material_return_lines', new.id, 2::smallint, h.project_id, h.subcontractor_id, new.material_id,
                           case when h.subcontractor_id is null then null else -new.qty end, h.returned_on);
  return new;
end $$;

create or replace function app.transfer_line_movement() returns trigger
language plpgsql security definer set search_path = public as $$
declare h transfer_orders;
begin
  if tg_op = 'DELETE' then perform app.drop_movements('transfer_order_lines', old.id); return old; end if;
  select * into h from transfer_orders where id = new.transfer_order_id;
  perform app.put_movement('transfer_order_lines', new.id, 1::smallint, h.project_id, null, new.material_id,
                           case when h.status = 'cancelled' then null else -new.qty end, h.sent_on);
  perform app.put_movement('transfer_order_lines', new.id, 2::smallint, h.to_project_id, null, new.material_id,
                           case when h.status = 'received' then new.qty else null end, h.received_on);
  return new;
end $$;

-- header changes (date, status, subcontractor) re-run their lines' movements
create or replace function app.rerun_lines() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  case tg_table_name
    when 'grns' then update grn_lines set updated_at = now() where grn_id = new.id;
    when 'material_issues' then update material_issue_lines set updated_at = now() where material_issue_id = new.id;
    when 'material_returns' then update material_return_lines set updated_at = now() where material_return_id = new.id;
    when 'transfer_orders' then update transfer_order_lines set updated_at = now() where transfer_order_id = new.id;
  end case;
  return new;
end $$;

create or replace function app.adjustment_movement() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'DELETE' then perform app.drop_movements('stock_adjustments', old.id); return old; end if;
  perform app.put_movement('stock_adjustments', new.id, 1::smallint, new.project_id, new.subcontractor_id,
                           new.material_id, new.qty, new.adjusted_on);
  return new;
end $$;

create trigger movement after insert or update or delete on grn_lines for each row execute function app.grn_line_movement();
create trigger movement after insert or update or delete on material_issue_lines for each row execute function app.issue_line_movement();
create trigger movement after insert or update or delete on material_return_lines for each row execute function app.return_line_movement();
create trigger movement after insert or update or delete on transfer_order_lines for each row execute function app.transfer_line_movement();
create trigger movement after insert or update or delete on stock_adjustments for each row execute function app.adjustment_movement();
create trigger rerun_lines after update on grns for each row execute function app.rerun_lines();
create trigger rerun_lines after update on material_issues for each row execute function app.rerun_lines();
create trigger rerun_lines after update on material_returns for each row execute function app.rerun_lines();
create trigger rerun_lines after update on transfer_orders for each row execute function app.rerun_lines();

-- ---------------------------------------------------------------- generic triggers

do $$
declare t text;
begin
  -- updated_at on every table that has it
  for t in select c.table_name from information_schema.columns c
           join information_schema.tables tb using (table_schema, table_name)
           where c.table_schema = 'public' and c.column_name = 'updated_at' and tb.table_type = 'BASE TABLE'
  loop
    execute format('create trigger set_updated_at before update on %I for each row execute function app.set_updated_at()', t);
  end loop;

  -- per-project numbers
  foreach t in array array['tasks','issues','work_orders','indents','purchase_orders','grns','transfer_requests',
                           'transfer_orders','material_issues','material_returns','payables']
  loop
    execute format('create trigger assign_number before insert on %I for each row execute function app.assign_number()', t);
  end loop;

  -- tombstones for every synced table
  foreach t in array array['projects','project_members','project_settings','task_categories','subcontractors','work_orders',
                           'work_order_lines','tasks','task_updates','issues','comments','activity_events','albums','vendors',
                           'materials','indents','indent_lines','purchase_orders','po_lines','grns','grn_lines',
                           'transfer_requests','transfer_request_lines','transfer_orders','transfer_order_lines',
                           'material_issues','material_issue_lines','material_returns','material_return_lines',
                           'stock_adjustments','stock_movements','workers','attendance','payables','payments','expenses',
                           'media','drive_files','reports','chat_messages']
  loop
    execute format('create trigger tombstone after delete on %I for each row execute function app.write_tombstone()', t);
  end loop;

  -- audit trail where users must be able to fix mistakes (users ask for this: recon src 11)
  foreach t in array array['attendance','grns','grn_lines','material_issues','material_issue_lines','stock_adjustments',
                           'payables','payments','expenses','workers']
  loop
    execute format('create trigger audit after update or delete on %I for each row execute function app.write_audit()', t);
  end loop;
end $$;

-- feed (S27): written here so it can't be forged by clients
create or replace function app.log_activity() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_verb text; v_summary text; v_actor uuid := auth.uid(); v_project uuid;
begin
  case tg_table_name
    when 'projects' then v_verb := 'created_project'; v_summary := new.name; v_project := new.id;
    when 'project_members' then v_verb := 'joined'; v_summary := new.role; v_actor := new.user_id;
    when 'tasks' then v_verb := 'created_task'; v_summary := new.name;
    when 'task_updates' then v_verb := 'updated_progress'; v_summary := coalesce(new.remarks, '');
    when 'issues' then
      if tg_op = 'INSERT' then v_verb := 'raised_issue';
      elsif new.status = 'closed' and old.status <> 'closed' then v_verb := 'closed_issue';
      else return new; end if;
      v_summary := new.title;
  end case;
  if v_project is null then v_project := (to_jsonb(new)->>'project_id')::uuid; end if;
  insert into activity_events (project_id, actor_id, verb, entity_type, entity_id, summary)
  values (v_project, v_actor, v_verb, tg_table_name, new.id, left(v_summary, 200));
  return new;
end $$;
create trigger log_activity after insert on projects for each row execute function app.log_activity();
create trigger log_activity after insert on project_members for each row execute function app.log_activity();
create trigger log_activity after insert on tasks for each row execute function app.log_activity();
create trigger log_activity after insert on task_updates for each row execute function app.log_activity();
create trigger log_activity after insert or update of status on issues for each row execute function app.log_activity();

-- new project: settings row + creator as manager
create or replace function app.on_project_created() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into project_settings (project_id) values (new.id) on conflict do nothing;
  if new.created_by is not null then
    insert into project_members (project_id, user_id, role) values (new.id, new.created_by, 'manager')
    on conflict (project_id, user_id) do nothing;
  end if;
  return new;
end $$;
create trigger on_project_created after insert on projects for each row execute function app.on_project_created();

-- ---------------------------------------------------------------- access helpers

create or replace function app.org_role(p_org uuid) returns text
language sql stable security definer set search_path = public as $$
  select role from org_members where org_id = p_org and user_id = auth.uid();
$$;

create or replace function app.is_org_member(p_org uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from org_members where org_id = p_org and user_id = auth.uid());
$$;

create or replace function app.is_org_admin(p_org uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce(app.org_role(p_org) in ('owner','admin'), false);
$$;

-- the caller's effective role on a project: org owners/admins act as managers everywhere.
create or replace function app.project_role(p_project uuid) returns text
language sql stable security definer set search_path = public as $$
  select case
    when exists (select 1 from projects p join org_members m on m.org_id = p.org_id
                 where p.id = p_project and m.user_id = auth.uid() and m.role in ('owner','admin')) then 'manager'
    else (select role from project_members where project_id = p_project and user_id = auth.uid())
  end;
$$;

create or replace function app.can_read(p_project uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select app.project_role(p_project) is not null;
$$;

create or replace function app.can_write(p_project uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce(app.project_role(p_project) <> 'viewer', false);
$$;

create or replace function app.can_see_money(p_project uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce(app.project_role(p_project) in ('manager','accountant'), false);
$$;

create or replace function app.can_manage(p_project uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce(app.project_role(p_project) = 'manager', false);
$$;

-- ---------------------------------------------------------------- row level security

alter table organisations enable row level security;
create policy read on organisations for select to authenticated using (app.is_org_member(id));
create policy write on organisations for update to authenticated using (app.is_org_admin(id)) with check (app.is_org_admin(id));
-- creating an organisation goes through rpc create_organisation (makes the caller owner in one transaction)

alter table profiles enable row level security;
create policy read on profiles for select to authenticated using (
  id = auth.uid() or exists (
    select 1 from org_members a join org_members b on a.org_id = b.org_id
    where a.user_id = auth.uid() and b.user_id = profiles.id));
create policy write on profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

alter table org_members enable row level security;
create policy read on org_members for select to authenticated using (app.is_org_member(org_id));
create policy write on org_members for all to authenticated using (app.is_org_admin(org_id)) with check (app.is_org_admin(org_id) and role <> 'owner');

alter table invites enable row level security;
create policy rw on invites for all to authenticated
  using (app.is_org_admin(org_id) or (project_id is not null and app.can_manage(project_id)))
  with check (app.is_org_admin(org_id) or (project_id is not null and app.can_manage(project_id) and org_role = 'member'));

alter table projects enable row level security;
create policy read on projects for select to authenticated using (app.can_read(id));
create policy ins on projects for insert to authenticated with check (app.is_org_admin(org_id) and created_by = auth.uid());
create policy upd on projects for update to authenticated using (app.can_manage(id)) with check (app.can_manage(id));
create policy del on projects for delete to authenticated using (app.is_org_admin(org_id));

alter table project_members enable row level security;
create policy read on project_members for select to authenticated using (app.can_read(project_id));
create policy write on project_members for all to authenticated using (app.can_manage(project_id)) with check (app.can_manage(project_id));

alter table project_settings enable row level security;
create policy read on project_settings for select to authenticated using (app.can_read(project_id));
create policy write on project_settings for update to authenticated using (app.can_manage(project_id)) with check (app.can_manage(project_id));

alter table doc_counters enable row level security;   -- no policies: only triggers touch it

-- org-level catalogues (materials, vendors, ...): site teams add to them, so any org member reads and writes
do $$
declare t text;
begin
  foreach t in array array['task_categories','subcontractors','vendors','materials'] loop
    execute format('alter table %I enable row level security', t);
    execute format('create policy rw on %I for all to authenticated using (app.is_org_member(org_id)) with check (app.is_org_member(org_id))', t);
  end loop;

  -- project data any non-viewer member may write
  foreach t in array array['tasks','task_updates','issues','comments','albums','indents','grns','transfer_requests',
                           'transfer_orders','material_issues','material_returns','stock_adjustments','workers',
                           'attendance','media','drive_files','reports'] loop
    execute format('alter table %I enable row level security', t);
    execute format('create policy read on %I for select to authenticated using (app.can_read(project_id))', t);
    execute format('create policy write on %I for all to authenticated using (app.can_write(project_id)) with check (app.can_write(project_id))', t);
  end loop;

  -- money: managers and accountants only
  foreach t in array array['purchase_orders','payables','payments','expenses','work_orders'] loop
    execute format('alter table %I enable row level security', t);
    execute format('create policy read on %I for select to authenticated using (app.can_see_money(project_id))', t);
    execute format('create policy write on %I for all to authenticated using (app.can_see_money(project_id)) with check (app.can_see_money(project_id))', t);
  end loop;
end $$;

-- line tables follow their header
alter table indent_lines enable row level security;
create policy rw on indent_lines for all to authenticated
  using (exists (select 1 from indents h where h.id = indent_id and app.can_read(h.project_id)))
  with check (exists (select 1 from indents h where h.id = indent_id and app.can_write(h.project_id)));

alter table po_lines enable row level security;
create policy rw on po_lines for all to authenticated
  using (exists (select 1 from purchase_orders h where h.id = po_id and app.can_see_money(h.project_id)))
  with check (exists (select 1 from purchase_orders h where h.id = po_id and app.can_see_money(h.project_id)));

alter table grn_lines enable row level security;
create policy rw on grn_lines for all to authenticated
  using (exists (select 1 from grns h where h.id = grn_id and app.can_read(h.project_id)))
  with check (exists (select 1 from grns h where h.id = grn_id and app.can_write(h.project_id)));

alter table transfer_request_lines enable row level security;
create policy rw on transfer_request_lines for all to authenticated
  using (exists (select 1 from transfer_requests h where h.id = transfer_request_id and (app.can_read(h.project_id) or app.can_read(h.source_project_id))))
  with check (exists (select 1 from transfer_requests h where h.id = transfer_request_id and app.can_write(h.project_id)));

alter table transfer_order_lines enable row level security;
create policy rw on transfer_order_lines for all to authenticated
  using (exists (select 1 from transfer_orders h where h.id = transfer_order_id and (app.can_read(h.project_id) or app.can_read(h.to_project_id))))
  with check (exists (select 1 from transfer_orders h where h.id = transfer_order_id and app.can_write(h.project_id)));

alter table material_issue_lines enable row level security;
create policy rw on material_issue_lines for all to authenticated
  using (exists (select 1 from material_issues h where h.id = material_issue_id and app.can_read(h.project_id)))
  with check (exists (select 1 from material_issues h where h.id = material_issue_id and app.can_write(h.project_id)));

alter table material_return_lines enable row level security;
create policy rw on material_return_lines for all to authenticated
  using (exists (select 1 from material_returns h where h.id = material_return_id and app.can_read(h.project_id)))
  with check (exists (select 1 from material_returns h where h.id = material_return_id and app.can_write(h.project_id)));

alter table work_order_lines enable row level security;
create policy rw on work_order_lines for all to authenticated
  using (exists (select 1 from work_orders h where h.id = work_order_id and app.can_see_money(h.project_id)))
  with check (exists (select 1 from work_orders h where h.id = work_order_id and app.can_see_money(h.project_id)));

-- transfers are visible to the receiving project too (it marks them received)
create policy read_incoming on transfer_orders for select to authenticated using (app.can_read(to_project_id));
create policy receive on transfer_orders for update to authenticated using (app.can_write(to_project_id)) with check (app.can_write(to_project_id));
create policy read_incoming on transfer_requests for select to authenticated using (app.can_read(source_project_id));
create policy decide on transfer_requests for update to authenticated using (app.can_write(source_project_id)) with check (app.can_write(source_project_id));

-- read-only to clients
alter table stock_movements enable row level security;
create policy read on stock_movements for select to authenticated using (app.can_read(project_id));

alter table activity_events enable row level security;
create policy read on activity_events for select to authenticated using (app.can_read(project_id));

alter table chat_messages enable row level security;
create policy read on chat_messages for select to authenticated
  using (case when project_id is null then app.is_org_member(org_id) else app.can_read(project_id) end);
create policy post on chat_messages for insert to authenticated
  with check (author_id = auth.uid() and case when project_id is null then app.is_org_member(org_id) else app.can_read(project_id) end);
create policy edit on chat_messages for update to authenticated using (author_id = auth.uid()) with check (author_id = auth.uid());
create policy del on chat_messages for delete to authenticated using (author_id = auth.uid());

alter table notifications enable row level security;
create policy own on notifications for select to authenticated using (user_id = auth.uid());
create policy mark_read on notifications for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

alter table devices enable row level security;
create policy own on devices for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

alter table subscriptions enable row level security;
create policy read on subscriptions for select to authenticated using (app.is_org_admin(org_id));

alter table tombstones enable row level security;
create policy read on tombstones for select to authenticated
  using ((project_id is not null and app.can_read(project_id)) or (org_id is not null and app.is_org_member(org_id)));

alter table audit_log enable row level security;
create policy read on audit_log for select to authenticated using (project_id is not null and app.can_manage(project_id));

alter table webhook_events enable row level security;   -- service role only

-- ---------------------------------------------------------------- rpc

create or replace function public.create_organisation(p_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare v uuid;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  insert into organisations (name) values (p_name) returning id into v;
  insert into org_members (org_id, user_id, role) values (v, auth.uid(), 'owner');
  return v;
end $$;

-- called after OTP sign-in: joins every pending invite for the caller's phone
create or replace function public.accept_invites() returns integer
language plpgsql security definer set search_path = public as $$
declare v_phone text; n integer := 0; i invites;
begin
  select phone into v_phone from profiles where id = auth.uid();
  if v_phone is null then return 0; end if;
  for i in select * from invites where phone = v_phone and accepted_at is null loop
    insert into org_members (org_id, user_id, role) values (i.org_id, auth.uid(), i.org_role)
      on conflict (org_id, user_id) do nothing;
    if i.project_id is not null then
      insert into project_members (project_id, user_id, role) values (i.project_id, auth.uid(), coalesce(i.project_role, 'engineer'))
        on conflict (project_id, user_id) do nothing;
    end if;
    update invites set accepted_at = now() where id = i.id;
    n := n + 1;
  end loop;
  return n;
end $$;

-- S23 "Review & Record Payable": wages from attendance for a period, one payable per worker.
create or replace function public.preview_attendance_payables(p_project uuid, p_from date, p_to date)
returns table (worker_id uuid, worker_name text, days numeric, hours numeric, ot_hours numeric, amount_paise bigint)
language sql stable security invoker set search_path = public as $$
  select w.id, w.name,
         sum(case a.status when 'present' then 1 when 'half_day' then 0.5 else 0 end) as days,
         sum(coalesce(a.hours, 0)) as hours,
         sum(a.ot_hours) as ot_hours,
         round(
           case w.wage_type
             when 'daily' then sum(case a.status when 'present' then 1 when 'half_day' then 0.5
                                                  when 'hours' then a.hours / s.shift_hours else 0 end) * w.wage_paise
             else sum(case a.status when 'present' then s.shift_hours when 'half_day' then s.shift_hours / 2
                                     when 'hours' then a.hours else 0 end) * w.wage_paise
           end + sum(a.ot_hours) * w.ot_rate_paise)::bigint as amount_paise
  from attendance a
  join workers w on w.id = a.worker_id
  join project_settings s on s.project_id = a.project_id
  where a.project_id = p_project and a.work_date between p_from and p_to
    -- days already in a live payable are not paid twice
    and not exists (select 1 from payables p where p.kind = 'attendance' and p.worker_id = w.id
                    and p.status <> 'void' and a.work_date between p.period_start and p.period_end)
  group by w.id, w.name, w.wage_type, w.wage_paise, w.ot_rate_paise
  having round(
           case w.wage_type
             when 'daily' then sum(case a.status when 'present' then 1 when 'half_day' then 0.5
                                                  when 'hours' then a.hours / s.shift_hours else 0 end) * w.wage_paise
             else sum(case a.status when 'present' then s.shift_hours when 'half_day' then s.shift_hours / 2
                                     when 'hours' then a.hours else 0 end) * w.wage_paise
           end + sum(a.ot_hours) * w.ot_rate_paise) > 0;
$$;

-- payment totals keep the payable status honest
create or replace function app.sync_payable_status() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_id uuid := coalesce(new.payable_id, old.payable_id); paid bigint; due bigint;
begin
  select coalesce(sum(amount_paise), 0) into paid from payments where payable_id = v_id;
  select amount_paise into due from payables where id = v_id;
  update payables set status = case when paid = 0 then 'due' when paid < due then 'partly_paid' else 'paid' end
  where id = v_id and status <> 'void';
  return coalesce(new, old);
end $$;
create trigger sync_payable_status after insert or update or delete on payments
  for each row execute function app.sync_payable_status();

grant usage on schema app to authenticated;
grant execute on all functions in schema app to authenticated;
