-- V3: per-account token version. Every access token carries the version it was issued with;
-- bumping it (logout on all devices) makes all of the account's access tokens invalid at once.
alter table user_accounts add column token_version integer default 0 not null;
