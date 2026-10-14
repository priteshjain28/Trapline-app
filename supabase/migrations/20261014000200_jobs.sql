-- Scheduled jobs. Needs pg_cron (enabled on Supabase from Dashboard > Database > Extensions).
-- Skipped quietly where pg_cron isn't available (local tests). Times are UTC; comments give India time.
-- Jobs that need the Storage or Razorpay APIs call Edge Functions; that wiring is in replica/backend.md.

do $$
begin
  if not exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    raise notice 'pg_cron not available: jobs not scheduled';
    return;
  end if;
  create extension if not exists pg_cron;

  -- 18:30 IST: one nudge per manager for active sites with nothing logged today
  perform cron.schedule('daily-nudge', '0 13 * * *', $job$ select app.daily_nudge() $job$);
  -- 06:00 IST: tasks that went overdue yesterday
  perform cron.schedule('overdue-tasks', '30 0 * * *', $job$ select app.overdue_task_alerts() $job$);
  -- 02:00 IST: expired reports. The cleanup function removes the PDF from Storage, then the row.
  -- Needs pg_net and two Vault secrets (project_url, cron_secret); see replica/backend.md.
  if exists (select 1 from pg_available_extensions where name = 'pg_net') then
    create extension if not exists pg_net;
    perform cron.schedule('expire-reports', '30 20 * * *', $job$
      select net.http_post(
        url := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url') || '/functions/v1/cleanup',
        headers := jsonb_build_object('x-cron-secret', (select decrypted_secret from vault.decrypted_secrets where name = 'cron_secret')),
        body := '{}'::jsonb)
    $job$);
  end if;
  -- hourly: forget old rate-limit windows and read notifications older than 60 days
  perform cron.schedule('housekeeping', '7 * * * *', $job$
    delete from public.rate_limits where window_start < now() - interval '1 day';
    delete from public.notifications where read_at is not null and read_at < now() - interval '60 days';
  $job$);
end $$;
