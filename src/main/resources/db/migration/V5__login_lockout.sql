-- V5: brute-force protection. Consecutive failed logins per account and a temporary lock.
alter table user_accounts add column failed_logins integer default 0 not null;
alter table user_accounts add column last_failed_login_at timestamp(6);
alter table user_accounts add column locked_until timestamp(6);
