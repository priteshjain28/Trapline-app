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
  -- 02:00 IST: drop expired report rows (their PDFs are removed by the cleanup function, see backend.md)
  perform cron.schedule('expire-reports', '30 20 * * *', $job$ delete from public.reports where expires_at < now() $job$);
  -- hourly: forget old rate-limit windows and read notifications older than 60 days
  perform cron.schedule('housekeeping', '7 * * * *', $job$
    delete from public.rate_limits where window_start < now() - interval '1 day';
    delete from public.notifications where read_at is not null and read_at < now() - interval '60 days';
  $job$);
end $$;
