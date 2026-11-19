-- Freeze the optional published input at discovery; retries must use the same shape.
ALTER TABLE route_display_generation_job
    ADD COLUMN previous_display_key varchar(64) CHECK (previous_display_key ~ '^[0-9a-f]{64}$');

CREATE OR REPLACE FUNCTION reject_route_display_job_input_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.generation_key,NEW.route_id,NEW.input_digest,NEW.evidence_revision,NEW.algorithm_revision,NEW.input_json,NEW.source_manifest_id,NEW.previous_display_key)
        IS DISTINCT FROM
       (OLD.generation_key,OLD.route_id,OLD.input_digest,OLD.evidence_revision,OLD.algorithm_revision,OLD.input_json,OLD.source_manifest_id,OLD.previous_display_key) THEN
        RAISE EXCEPTION 'route display generation input is immutable';
    END IF;
    RETURN NEW;
END;
$$;
