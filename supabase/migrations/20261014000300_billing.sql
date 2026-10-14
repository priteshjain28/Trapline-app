-- Razorpay sends webhooks at least once and not always in order. Remember the newest event applied
-- per subscription so an older one can't move it backwards.
alter table subscriptions add column last_event_at timestamptz;
