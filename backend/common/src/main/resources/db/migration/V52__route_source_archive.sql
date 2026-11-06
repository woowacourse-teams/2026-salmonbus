CREATE TABLE route_source_blob (
    body_sha256 varchar(64) PRIMARY KEY,
    body bytea NOT NULL CHECK (octet_length(body) > 0),
    CHECK (body_sha256 = encode(sha256(body),'hex'))
);
CREATE TABLE route_source_receipt (
    receipt_id varchar(64) PRIMARY KEY,
    document text NOT NULL CHECK (jsonb_typeof(document::jsonb)='object'),
    body_sha256 varchar(64) REFERENCES route_source_blob(body_sha256),
    CHECK (receipt_id = encode(sha256(convert_to(document,'UTF8')),'hex'))
);
CREATE TABLE route_source_manifest (
    manifest_id varchar(64) PRIMARY KEY,
    purpose varchar(64) NOT NULL CHECK (purpose ~ '^[A-Z0-9_]{1,64}$'),
    document text NOT NULL CHECK (jsonb_typeof(document::jsonb)='object'),
    CHECK (manifest_id = encode(sha256(convert_to(document,'UTF8')),'hex'))
);
CREATE TABLE route_source_manifest_receipt (
    manifest_id varchar(64) NOT NULL REFERENCES route_source_manifest(manifest_id),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    receipt_id varchar(64) NOT NULL REFERENCES route_source_receipt(receipt_id),
    PRIMARY KEY (manifest_id,ordinal)
);
CREATE TABLE route_source_manifest_reuse (
    manifest_id varchar(64) NOT NULL REFERENCES route_source_manifest(manifest_id),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    reused_manifest_id varchar(64) NOT NULL REFERENCES route_source_manifest(manifest_id),
    PRIMARY KEY (manifest_id,ordinal),
    CHECK (manifest_id <> reused_manifest_id)
);
CREATE TABLE route_input_manifest (
    manifest_id varchar(64) PRIMARY KEY,
    route_id bigint NOT NULL REFERENCES route(id),
    reference_version_id bigint NOT NULL REFERENCES route_version(id),
    input_digest varchar(64) NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    evidence_revision text NOT NULL,
    acquisition_manifest_id varchar(64) NOT NULL REFERENCES route_source_manifest(manifest_id),
    document text NOT NULL CHECK (jsonb_typeof(document::jsonb)='object'),
    CHECK (manifest_id = encode(sha256(convert_to(document,'UTF8')),'hex'))
);
CREATE INDEX route_input_manifest_identity ON route_input_manifest(input_digest,evidence_revision);

CREATE FUNCTION reject_route_source_archive_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'route source archives are immutable';
END;
$$;
DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['route_source_blob','route_source_receipt','route_source_manifest',
        'route_source_manifest_receipt','route_source_manifest_reuse','route_input_manifest'] LOOP
        EXECUTE format('CREATE TRIGGER source_archive_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_route_source_archive_mutation()',table_name);
    END LOOP;
END;
$$;

ALTER TABLE route_geometry ADD COLUMN source_manifest_id varchar(64) REFERENCES route_input_manifest(manifest_id);
ALTER TABLE route_display_generation_job ADD COLUMN source_manifest_id varchar(64) REFERENCES route_input_manifest(manifest_id);
ALTER TABLE route_display_automatic_artifact ADD COLUMN source_manifest_id varchar(64) REFERENCES route_input_manifest(manifest_id);
ALTER TABLE route_geometry_source_cache ADD COLUMN acquisition_manifest_id varchar(64) REFERENCES route_source_manifest(manifest_id);

CREATE OR REPLACE FUNCTION reject_route_display_job_input_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.generation_key,NEW.route_id,NEW.input_digest,NEW.evidence_revision,NEW.algorithm_revision,NEW.input_json,NEW.source_manifest_id)
        IS DISTINCT FROM
       (OLD.generation_key,OLD.route_id,OLD.input_digest,OLD.evidence_revision,OLD.algorithm_revision,OLD.input_json,OLD.source_manifest_id) THEN
        RAISE EXCEPTION 'route display generation input is immutable';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE route_version ADD COLUMN acquisition_manifest_id varchar(64) REFERENCES route_source_manifest(manifest_id);
ALTER TABLE route_version ADD COLUMN catalog_acquisition_observed_at timestamptz;
