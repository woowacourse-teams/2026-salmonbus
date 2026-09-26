ALTER TABLE daily_call_quota
    ADD COLUMN key_alias varchar(16) NOT NULL DEFAULT 'a';

ALTER TABLE daily_call_quota DROP CONSTRAINT pk_daily_call_quota;
ALTER TABLE daily_call_quota
    ADD CONSTRAINT pk_daily_call_quota PRIMARY KEY (provider, api_service, kst_date, key_alias);
