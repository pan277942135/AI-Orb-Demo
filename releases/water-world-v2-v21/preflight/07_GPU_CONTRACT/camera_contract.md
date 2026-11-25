# Camera Contract

The master canvas is 1254x1254 per inherited V2 scene metadata; final camera framing remains uncalibrated until raw master bytes are analyzed. Asset placement coordinates use normalized canvas estimates. Contract origin is top-left for these source coordinates, with x right and y down. For normalized u,v in [0,1], canvas pixel is (u*W,v*H); the conventional clip mapping is x=2u-1, y=1-2v. This is a coordinate conversion contract, not evidence of calibrated geometry.

World, master, object-local, view and clip spaces must be explicit. Content scale/offset and aspect behavior must preserve the square reference canvas. Core motion uses Core Local 3D and never changes screen-space coordinates directly.
