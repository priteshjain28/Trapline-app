-- Company logo (S29). Stored under org-assets/<org_id>/..., readable by everyone in the company,
-- replaceable by its owners and admins. Project cover images use project-media/<project_id>/cover-*.jpg.

create or replace function app.path_org(p_name text) returns uuid
language sql immutable as $$
  select case when split_part(p_name, '/', 1) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              then split_part(p_name, '/', 1)::uuid end;
$$;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('org-assets', 'org-assets', false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy "company files: read" on storage.objects for select to authenticated
  using (bucket_id = 'org-assets' and app.is_org_member(app.path_org(name)));
create policy "company files: add" on storage.objects for insert to authenticated
  with check (bucket_id = 'org-assets' and app.is_org_admin(app.path_org(name)));
create policy "company files: replace" on storage.objects for update to authenticated
  using (bucket_id = 'org-assets' and app.is_org_admin(app.path_org(name)));
create policy "company files: delete" on storage.objects for delete to authenticated
  using (bucket_id = 'org-assets' and app.is_org_admin(app.path_org(name)));

-- a logo path must point inside the company's own folder
alter table organisations add constraint logo_in_own_folder
  check (logo_path is null or logo_path like id::text || '/%');
alter table projects add constraint image_in_own_folder
  check (image_path is null or image_path like id::text || '/%');
