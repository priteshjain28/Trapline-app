-- The app records a task's category as typed (e.g. "Masonry"). category_id stays for a managed list later.
alter table tasks add column category text not null default 'General' check (length(category) between 1 and 60);
