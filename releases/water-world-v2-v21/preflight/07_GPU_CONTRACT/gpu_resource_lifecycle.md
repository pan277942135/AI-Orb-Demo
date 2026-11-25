# GPU Resource Lifecycle

1. Read immutable source bytes and verify SHA. 2. Decode off GL thread with scale/premultiplication policy explicit. 3. Validate dimensions, color profile, channels and bounds. 4. Upload only on active EGL context thread. 5. Set texture target/filter/wrap/format from verified capabilities. 6. Check GL errors and framebuffer completeness after allocation and each capture pass. 7. Record resource IDs and source hashes. 8. On context loss, discard stale GL names and re-upload; publish LOADING/READY/ERROR. 9. Release resources on owning context.

V2 dimensions include 1254x1254 assets and multiple 512x512 maps; API36 device texture limits must be queried. AI Orb manifest uses 256x256x32 volume and checks GL_MAX_3D_TEXTURE_SIZE. No GPU allocation is claimed in this preflight.
