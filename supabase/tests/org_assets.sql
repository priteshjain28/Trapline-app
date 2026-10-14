-- Tests for 20261014000500_org_assets.sql. Run after smoke.sql (owner A, engineer B, outsider C).
\set ON_ERROR_STOP 1
set client_min_messages = warning;
reset role;

create or replace function pg_temp.ok(cond boolean, msg text) returns void language plpgsql as
$$ begin if cond is not true then raise exception 'FAIL: %', msg; end if; end $$;
create or replace function pg_temp.as_user(u uuid) returns void language sql as
$$ select set_config('request.jwt.claim.sub', u::text, false) $$;

create temp table org as select org_id as id from projects where id = '10000000-0000-0000-0000-000000000001';
grant select on org to authenticated;
-- E: a plain member of the company (B left in server_rules.sql)
insert into auth.users (id, phone) values ('00000000-0000-0000-0000-00000000000e', '910000000005');
insert into org_members (org_id, user_id, role) select id, '00000000-0000-0000-0000-00000000000e', 'member' from org;

set role authenticated;
-- the owner sets a logo
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
insert into storage.objects (bucket_id, name) select 'org-assets', id::text || '/logo-1.jpg' from org;
update organisations set logo_path = (select id::text || '/logo-1.jpg' from org) where id = (select id from org);
select pg_temp.ok((select logo_path is not null from organisations where id = (select id from org)), 'owner sets the logo');

-- a path outside the company's folder is refused
do $$ begin
  update organisations set logo_path = 'someone-else/logo.jpg' where id = (select id from org);
  raise exception 'FAIL: logo outside the company folder accepted';
exception when check_violation then null; end $$;

-- a member sees it but can't replace it
select pg_temp.as_user('00000000-0000-0000-0000-00000000000e');
select pg_temp.ok((select count(*) = 1 from storage.objects where bucket_id = 'org-assets'), 'members see the logo');
do $$ begin
  insert into storage.objects (bucket_id, name) select 'org-assets', id::text || '/logo-2.jpg' from org;
  raise exception 'FAIL: a member uploaded a company logo';
exception when insufficient_privilege then null; end $$;

-- the outsider sees nothing
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
select pg_temp.ok((select count(*) = 0 from storage.objects where bucket_id = 'org-assets'), 'outsiders see no company files');

-- a project image lives in the project's own folder
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
insert into storage.objects (bucket_id, name) values ('project-media', '10000000-0000-0000-0000-000000000001/cover-1.jpg');
update projects set image_path = '10000000-0000-0000-0000-000000000001/cover-1.jpg' where id = '10000000-0000-0000-0000-000000000001';
do $$ begin
  update projects set image_path = '10000000-0000-0000-0000-000000000002/cover.jpg' where id = '10000000-0000-0000-0000-000000000001';
  raise exception 'FAIL: project image outside its folder accepted';
exception when check_violation then null; end $$;
reset role;
select 'org assets: ok';
