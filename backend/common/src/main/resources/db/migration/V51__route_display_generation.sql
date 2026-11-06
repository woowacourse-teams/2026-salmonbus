-- Jobs survive worker restarts; generation keys bind immutable source/reference/algorithm inputs.
CREATE TABLE route_display_generation_job (
    generation_key varchar(64) PRIMARY KEY CHECK (generation_key ~ '^[0-9a-f]{64}$'),
    route_id varchar(64) NOT NULL,
    input_digest varchar(64) NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    evidence_revision text NOT NULL,
    algorithm_revision text NOT NULL,
    input_json jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','RUNNING','COMPLETED','SUPERSEDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX route_display_generation_claim ON route_display_generation_job(status,next_attempt_at,lease_until);

CREATE TABLE route_display_automatic_artifact (
    generation_key varchar(64) PRIMARY KEY REFERENCES route_display_generation_job(generation_key),
    route_id varchar(64) NOT NULL,
    input_digest varchar(64) NOT NULL,
    evidence_revision text NOT NULL,
    algorithm_revision text NOT NULL,
    document text NOT NULL CHECK (jsonb_typeof(document::jsonb) = 'object'),
    document_sha256 varchar(64) NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX route_display_automatic_lookup ON route_display_automatic_artifact(route_id,input_digest,evidence_revision,algorithm_revision);

-- Published artifacts are append-only even if a faulty caller attempts an UPDATE.
CREATE FUNCTION reject_route_display_artifact_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'route display artifacts are immutable';
END;
$$;
CREATE TRIGGER route_display_artifact_immutable BEFORE UPDATE OR DELETE
ON route_display_automatic_artifact FOR EACH ROW EXECUTE FUNCTION reject_route_display_artifact_mutation();

CREATE FUNCTION reject_route_display_job_input_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.generation_key,NEW.route_id,NEW.input_digest,NEW.evidence_revision,NEW.algorithm_revision,NEW.input_json)
        IS DISTINCT FROM
       (OLD.generation_key,OLD.route_id,OLD.input_digest,OLD.evidence_revision,OLD.algorithm_revision,OLD.input_json) THEN
        RAISE EXCEPTION 'route display generation input is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER route_display_job_input_immutable BEFORE UPDATE
ON route_display_generation_job FOR EACH ROW EXECUTE FUNCTION reject_route_display_job_input_mutation();
