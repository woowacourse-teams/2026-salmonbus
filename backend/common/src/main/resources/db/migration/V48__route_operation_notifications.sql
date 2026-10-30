SET LOCAL lock_timeout = '5s';

CREATE FUNCTION notify_route_operation_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        PERFORM pg_notify('salmonbus_route_operations', '');
        RETURN NULL;
    END IF;

    -- Repeated acknowledgements/publications must not invalidate every reader.
    IF TG_OP = 'UPDATE' THEN
        IF (to_jsonb(OLD) - 'applied_at') IS NOT DISTINCT FROM (to_jsonb(NEW) - 'applied_at') THEN
            RETURN NULL;
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        PERFORM pg_notify('salmonbus_route_operations', OLD.source_route_id);
        RETURN NULL;
    END IF;

    PERFORM pg_notify('salmonbus_route_operations', NEW.source_route_id);
    RETURN NULL;
END;
$$;

CREATE TRIGGER route_operation_changed
AFTER INSERT OR UPDATE OR DELETE ON route_operation
FOR EACH ROW EXECUTE FUNCTION notify_route_operation_change();

CREATE TRIGGER route_operation_cleared
AFTER TRUNCATE ON route_operation
FOR EACH STATEMENT EXECUTE FUNCTION notify_route_operation_change();
