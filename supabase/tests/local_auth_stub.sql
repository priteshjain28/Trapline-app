-- Plain-Postgres stand-in for the parts of Supabase the migrations rely on.
-- Only for running the migrations and tests locally; never apply to Supabase.
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then create role service_role nologin bypassrls; end if;
end $$;

create schema auth;
create table auth.users (id uuid primary key, phone text, email text);
create function auth.uid() returns uuid language sql stable as
  $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth to authenticated, anon;
grant execute on function auth.uid() to authenticated, anon;

-- Supabase Storage keeps file metadata in these two tables; policies on storage.objects decide access.
create schema storage;
create table storage.buckets (
  id text primary key, name text not null, public boolean default false,
  file_size_limit bigint, allowed_mime_types text[]
);
create table storage.objects (
  id uuid primary key default gen_random_uuid(),
  bucket_id text references storage.buckets(id),
  name text not null,
  owner uuid,
  created_at timestamptz default now(),
  unique (bucket_id, name)
);
alter table storage.objects enable row level security;
grant usage on schema storage to authenticated;
grant all on storage.objects to authenticated;
grant select on storage.buckets to authenticated;

-- Supabase grants new public tables and functions to its API roles when they are created
-- (default privileges); migrations then revoke what must stay server-only.
alter default privileges in schema public grant all on tables to authenticated, anon, service_role;
alter default privileges in schema public grant execute on functions to authenticated, anon, service_role;
alter default privileges in schema public grant usage, select on sequences to authenticated, anon, service_role;
grant usage on schema public to authenticated, anon, service_role;
