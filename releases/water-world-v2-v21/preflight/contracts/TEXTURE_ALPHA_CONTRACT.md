# Texture and Alpha Contract

No unverified format is frozen as production output. Resource audit records PNG mode and ICC presence; inherited V2 alpha-bearing PNGs are straight-alpha candidates, but premultiplication is unverified and must be tested at transparent edges. Normal, depth, mask and caustics data are linear data; color albedo is sRGB; alpha density/masks are linear. Do not apply sRGB transfer to alpha.

Blend contract: straight-alpha RGB uses SRC_ALPHA / ONE_MINUS_SRC_ALPHA; premultiplied RGB uses ONE / ONE_MINUS_SRC_ALPHA. FBO alpha uses ONE / ONE_MINUS_SRC_ALPHA. Never mix these conventions within a pass. Convert once at decode/upload boundary if needed. Preserve full RGB under transparent texels or dilate edge color to avoid halos.

AI Orb reference manifest declares volume RGB sRGB + linear alpha and GL_SRGB8_ALPHA8; separate detail/caustics maps are linear RGBA8; environment is sRGB. These are reference contracts only and need device verification in Work 2.
