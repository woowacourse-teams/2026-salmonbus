# Raw data policy

This directory intentionally contains no collector object, response body, row-level export,
vehicle identifier, plate value, or HMAC.

The reproducible build lists and gets the frozen S3 closure, validates record/raw body hashes and
the route roster, and transforms accepted rows in process. Only de-identified derived feature rows
are written to an automatically removed build directory. Durable source evidence is aggregate-only
and lives in `../processed/source-audit.json` and `../processed/source-load-receipt.json`.
