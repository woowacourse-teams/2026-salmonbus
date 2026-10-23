SET LOCAL lock_timeout = '1s';
ALTER TABLE route_version
    ADD COLUMN weekday_min_headway_minutes integer,
    ADD COLUMN weekday_max_headway_minutes integer,
    ADD COLUMN saturday_min_headway_minutes integer,
    ADD COLUMN saturday_max_headway_minutes integer,
    ADD COLUMN sunday_min_headway_minutes integer,
    ADD COLUMN sunday_max_headway_minutes integer,
    ADD COLUMN holiday_min_headway_minutes integer,
    ADD COLUMN holiday_max_headway_minutes integer,
    ADD CONSTRAINT ck_route_version_weekday_headway CHECK (
        (weekday_min_headway_minutes IS NULL AND weekday_max_headway_minutes IS NULL)
        OR (weekday_min_headway_minutes IS NOT NULL AND weekday_max_headway_minutes IS NOT NULL
            AND weekday_min_headway_minutes BETWEEN 1 AND 1440
            AND weekday_max_headway_minutes BETWEEN weekday_min_headway_minutes AND 1440)),
    ADD CONSTRAINT ck_route_version_saturday_headway CHECK (
        (saturday_min_headway_minutes IS NULL AND saturday_max_headway_minutes IS NULL)
        OR (saturday_min_headway_minutes IS NOT NULL AND saturday_max_headway_minutes IS NOT NULL
            AND saturday_min_headway_minutes BETWEEN 1 AND 1440
            AND saturday_max_headway_minutes BETWEEN saturday_min_headway_minutes AND 1440)),
    ADD CONSTRAINT ck_route_version_sunday_headway CHECK (
        (sunday_min_headway_minutes IS NULL AND sunday_max_headway_minutes IS NULL)
        OR (sunday_min_headway_minutes IS NOT NULL AND sunday_max_headway_minutes IS NOT NULL
            AND sunday_min_headway_minutes BETWEEN 1 AND 1440
            AND sunday_max_headway_minutes BETWEEN sunday_min_headway_minutes AND 1440)),
    ADD CONSTRAINT ck_route_version_holiday_headway CHECK (
        (holiday_min_headway_minutes IS NULL AND holiday_max_headway_minutes IS NULL)
        OR (holiday_min_headway_minutes IS NOT NULL AND holiday_max_headway_minutes IS NOT NULL
            AND holiday_min_headway_minutes BETWEEN 1 AND 1440
            AND holiday_max_headway_minutes BETWEEN holiday_min_headway_minutes AND 1440));
