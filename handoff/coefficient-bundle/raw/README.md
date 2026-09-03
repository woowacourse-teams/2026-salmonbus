# Raw snapshot policy

No row-level collector object is stored here.

The private collector records contain original vehicle identifiers, plate values,
and HMAC join keys. The approved production builder is specifically designed to
consume those fields in memory and to emit only an aggregate receipt plus the
numeric bundle. Copying even a small original object into a handoff package would
violate that boundary and would not be sufficient to reproduce the 10-day fit.

Safe aggregate source closure is recorded in `aggregate-build-receipt.json`; the
schema-only and count-only live inventory is in `../processed/source-inventory.json`.

The receipt's `validated_not_deployed` status is historical as of 2026-08-24.
The 2026-09-02 serving snapshot independently proves that this candidate was
later deployed and is now the active shadow model.
