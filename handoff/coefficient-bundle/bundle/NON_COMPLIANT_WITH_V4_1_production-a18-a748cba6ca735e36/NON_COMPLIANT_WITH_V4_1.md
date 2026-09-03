# NON_COMPLIANT_WITH_V4_1

This is the byte-exact coefficient bundle currently observed in the EC2 shadow
runtime. It passes its production Python contract, but it does not satisfy the
current dev Java bundle consumer contract at commit
`b239691512dc5c498ae7013cd786e0b627f0c010`.

The two original filenames are intentionally preserved because renaming
`manifest.json` or `weights.safetensors` would invalidate the production bundle.
Do not point `MODEL_BUNDLE_DIRECTORY` at this directory.

See `../../CONTRACT-COMPLIANCE.md` and `../../VALIDATION.md`.
