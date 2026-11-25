# Asset Runtime Contract

Every uploaded resource has a source path, byte SHA256, decoded dimensions, format, color-space contract, alpha convention, GL target/format decision and owning context. Load is two-stage: CPU read/decode/validate, then GL-thread upload. The runtime must verify decode dimensions, channel count, checksum before decode where feasible, limits, GL errors and texture handles. Context loss invalidates every GL name and requires re-upload from immutable CPU/source bytes. Failed production assets are explicit errors, never silent shader fallback.

For V2, PNG/SVG/JSON assets remain source-authentic. Actual alpha premultiplication, ICC handling and GPU formats must be verified during implementation. For AI Orb reference assets, manifest and current loader specify 32 RGBA slices forming one GL_TEXTURE_3D and three 2D maps; read-only audit SHA checks passed. No GPU upload is performed by this contract task.
