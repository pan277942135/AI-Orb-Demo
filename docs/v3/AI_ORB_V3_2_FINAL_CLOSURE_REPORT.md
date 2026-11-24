# AI Orb V3.2 最终收尾 V3 实施报告

**Task ID:** `AI_ORB_V32_FINAL_CLOSURE_YUJIAN_CORE_OPTICAL_FUSION_V3`  
**评估母版:** `public/assets/aistudio/____________910d1531.png`  
**母版 SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`  
**执行分支:** `codex/ai-orb-v32-final-closure-optical-fusion-v3`  
**Main 基线:** `07ce54a6657edc57b2106d734b57ca3cec87545e`  
**GPU 证据:** `PENDING_REAL_GPU_CAPTURE`

## 1. 范围与基线审计

本轮从 Main 的指定 HEAD 建立独立工作分支。Stage A 重算母版投影以审计，但将冻结 Camera 值恢复后用于其余步骤；Stage D 只审计冻结的 Resident 曲线；Stage E 不回写 Ribbon 材质。核心外的 Renderer、布局和背景文件未进入修改集。Camera Snapshot 与六帧 Capture 系统保留原实现，并由现有合同测试覆盖。

| 模块 | 基线实现 | 已证实问题 | 本轮操作与证据 |
|---|---|---|---|
| Canonical Volume | 有解析体积 Evaluator | 闭环测试传参/解包不匹配；反向 smoothstep 使用倒序边界 | 统一完整输入、5 个返回值及明确 inverse smoothstep；增加边界/单调/非有限值用例 |
| 五项离线指标 | Stage C 有旧结果 | E05 centroid、E06 area/centroid、E07 area/centroid 超限 | 确定性联合拟合，96×96 投影、16 步独立复验；17/17 hard gates 通过 |
| WebP 资产 | 已有 32 张旧 Slice | 体积源与 WebP 未形成全像素闭环；FFmpeg WebP 路径会丢弃透明像素隐藏 RGB | Pillow `lossless=True, exact=True` 重生成；32 Slice 解码 RGBA 与 Canonical 字节逐通道相等 |
| Camera | 已有统一 Snapshot/Builder | Stage A 拟合器会写回冻结 Camera | Stage A 只测量，恢复冻结值；现有 Camera Snapshot 合同测试保留 |
| GPU Capture | 已有六帧 Capture | 当前执行环境没有可确认的硬件 GLES 设备及 PNG 证据 | 未伪造 GPU 结果；标记 `PENDING_REAL_GPU_CAPTURE`，捕获入口和六帧 Snapshot 合同保留 |
| Core Optics | 原 V3.2 玻璃与体积 Shader | 外壳法线变换、两界面 IOR/厚度、折叠法线采样坐标及吸收路径未统一 | 只改内核光学和参数链；软件 GLES 编译/链接通过，实机视觉仍待硬件证据 |

## 2. 根因与修复

| 问题 | 根因 | 修改位置 | 验证 |
|---|---|---|---|
| Canonical 测试失败/漏测 | Evaluator 要求完整 7 个输入并返回 RGB、Linear Density、Sheet Ridge 五项；测试使用旧合同 | `canonical_fluid_volume.py`, `test_v32_closure_gates.py` | 32×256×256 RGBA 全量比较，2,097,152 像素 |
| 反向渐变定义不一致 | `smoothstep()` 依赖负向边界，而 GLSL 定义未规定该行为 | `canonical_fluid_volume.py`, `CoreFluidShaders.kt`, `AIOrbFluidMath.kt` | 有序边界、独立 `inverseSmoothstep`，覆盖 NaN/Infinity、端点和中点 |
| 透明 WebP 隐藏 RGB 丢失 | FFmpeg `libwebp` 参数未启用 `exact`，透明像素 RGB 被规范化 | `generate_orb_volume.py`, `requirements.txt` | Pillow exact 编码后所有 decoded RGBA SHA 与 Canonical Slice 一致 |
| Stage B haze 12.76% 与 Stage E 0% 冲突 | Stage B 用整球像素作为分母，Stage E 用上部圆顶分母 | `visual_metrics.py`, `cavity_geometry_fitter.py`, `optics_fitter.py` | 统一 `ny>0.18`、排除 flare、luma/saturation 分类；Stage B 0.42%，Stage E 独立渲染 0.00% |
| 运行密度偏离拟合值 | 用户配置绝对 Density、Master 与 Kotlin fallback 各自赋值，Shader clamp 不匹配 | `AIOrbConfig.kt`, `MasterFidelityV32.kt`, `OrbFluidVolumeRenderer.kt`, `CoreFluidShaders.kt` | 默认倍率 1.0；Master density 2.2664，范围 0.8–2.4；跨 JSON/fallback/Runtime/Uniform 合同测试 |
| 体积法线错位 | 颜色/密度在变形坐标采样，法线却在原始坐标求梯度 | `CoreFluidShaders.kt` `sampleFluidVolume3D` / `estimateFluidFoldNormal` | 三轴中心差分统一到变形后 Sample Position，GLES Shader 实际编译链接 |
| 玻璃与水体界面不统一 | 硬编码折射率/起点偏移，法线直接模型变换 | `CoreFluidShaders.kt` `CORE_GLASS_VERT` / `CORE_GLASS_FRAG`, `OrbFluidVolumeRenderer.kt` | 逆转置法线矩阵、Master IOR、玻璃厚度及空气-玻璃-水体两界面参数接线 |
| 固定深度焦散贴片 | 使用固定 `tExit * 0.74` 单点计算 | `CoreFluidShaders.kt` `samplePathCaustic` | 三个体积路径深度样本按局部密度、腔体位置和 Beer-Lambert 衰减积分 |

## 3. 五项离线视觉指标

目标值沿用冻结门槛，未修改 Master PNG 测量区域或分类阈值。旧值来自指定 Main HEAD 的 `AI_ORB_V3_2_VISUAL_LOSS.json`；新值由本轮 Stage A–F 实际执行产生。

| 指标 | 修改前 | 修改后 | 门槛 | 结果 |
|---|---:|---:|---:|---|
| E05 Emerald centroid | 0.0413 D | 0.0367 D | ≤0.04 D | PASS |
| E06 Sapphire area error | 27.55% | 10.98% | ≤15% | PASS |
| E06 Sapphire centroid | 0.0862 D | 0.0173 D | ≤0.04 D | PASS |
| E07 Aqua area error | 20.39% | 3.39% | ≤15% | PASS |
| E07 Aqua centroid | 0.1261 D | 0.0283 D | ≤0.04 D | PASS |

修改后投影 Stage C 的 E03/E05/E06/E07 面积误差依次为 4.41%、8.22%、10.98%、3.39%，质心误差依次为 0.0216、0.0367、0.0173、0.0283 D。最终 Offline hard gates 为 17/17 PASS。独立解码 WebP 投影在 96×96、16 ray steps 下也通过 17/17 门禁：E03/E05/E06/E07 面积误差为 2.84%、6.67%、12.32%、4.82%，质心误差为 0.0194、0.0367、0.0168、0.0306 D。

## 4. 三维 Volume 与参数闭环

- 正式资产为 `256×256×32`、32 张 lossless RGBA WebP；RGB 为 sRGB，Alpha 为 Linear Density，未预乘 Alpha，球外 Alpha 为 0。
- Volume 不烘焙 Meniscus 或液面切割；GPU 仍以统一 Cavity SDF 管理液体边界。
- `CANONICAL_VOLUME_PIXEL_CLOSURE.json` 记录 32 个 Slice 的 RGBA 解码 SHA 与字节数。总计 2,097,152 voxels 全字节相等，最大量化误差 0.001880。
- `ORB_V3_ASSET_MANIFEST.json` 与 `SHA256SUMS` 记录 32 个 Volume Slice 和 3 个 Map 的逐资产 SHA。35/35 WebP 资产校验通过。
- `validate_fit.py` 对实际解码 WebP 执行三线性三维采样及独立投影；`validate_volume_continuity.py` 验证 360° 循环闭合误差 0，球外 Alpha 为 0。
- Master 配置 SHA-256：`e1b00b49565a3bfb4932b1b970e98da92e10028fadcbe0de9dbf74695c00bb7a`。视觉损失文件引用同一 SHA。
- `MasterFidelityV32.toRuntimeSummaryJson()` 已输出 glass、lens、fold、volume、吸收、颜色区域、caustic 参数，供 `RUNTIME_RENDER_CONFIG.json` 取证。

32 张切片的容器 SHA-256、RGBA 解码 SHA-256、字节数见下表，并与正式清单交叉校验：

| Z | 文件 | WebP 字节 | WebP SHA-256 | 解码 RGBA 字节 | 解码 RGBA SHA-256 |
|---:|---|---:|---|---:|---|
| 00 | `volume/fluid_color_z00.webp` | 1016 | `e484debced8ddf2b2dd974d0d533eda810e1d7432a17832ef3634a7e736a3c0c` | 262144 | `b98a004d83053d47bf0da11b6e5a7352d6bdedf23cbbef9c7bb0e3bed47a6292` |
| 01 | `volume/fluid_color_z01.webp` | 4360 | `0e74176fa474e9de9e7d01b2998cf7ed161fb0e2e00725efcc4db53e631c3795` | 262144 | `8f82fd20bf7759d6cf7940cb8727e7ca1b65134a17c75e5bd76ea6a3b6a07088` |
| 02 | `volume/fluid_color_z02.webp` | 8152 | `a5fe6a01fd5fe0731b2c5917d3f154ffe301fdf89651cb5f36e71b9afa509dbe` | 262144 | `4d4cc5ca8a8a2e7a60b5f3bcd125fdfa9afc41ae5f14b0c4e4f0794430049cf5` |
| 03 | `volume/fluid_color_z03.webp` | 14780 | `dc78e2020854b4c5b00d3926d3d43ef14c37da273402b57a565a4c13e0c7f2d7` | 262144 | `ac8a69407202747c5ce670fb292fa1d2eca9e4e6241b8fb24e954adf712cd71f` |
| 04 | `volume/fluid_color_z04.webp` | 20440 | `ae2eafe37638f691dc1556ea4fd50853affe70cac06541f036dd8c7257fe0499` | 262144 | `cd412de4475269442ca1fd3e89ead2e46583fc651cbd5d66d7f0f9dbbae25c1a` |
| 05 | `volume/fluid_color_z05.webp` | 23888 | `27a8d63bd402c57a8f085a0db63ed91d18fa70ea081d2d25ab6b7b2161d13d68` | 262144 | `623a74a024a12b9a7594d6a075bd08d85a28a9917093dc83674366b27960b274` |
| 06 | `volume/fluid_color_z06.webp` | 26438 | `240e075c1b5bc0391d4ae693851b1756c023f2540486cd3a1ad43250301aafef` | 262144 | `0342839de4a4a697dc6a6f9927cae29a6a09574da52d5c0c036535ded8b8b707` |
| 07 | `volume/fluid_color_z07.webp` | 27290 | `ce08f2e791ddae88401e6e837f559c9f17bf4d24f05ac2172f84cae5be56e3ee` | 262144 | `77d92d32e1fd08ad43529b1d435c3754d7aacecc1b6014d8cd3b91c479c4628e` |
| 08 | `volume/fluid_color_z08.webp` | 26082 | `e8798b0fb13692efc2fa171676c4dc57fe7b3803a0ad642c16329db9b69b0694` | 262144 | `ac007bb50b55ad82879364a9cebfe465772c1c945f7dfee31b854f33bc35a0ae` |
| 09 | `volume/fluid_color_z09.webp` | 24480 | `b1983f0cd691044d3286fd4b35ab27362341db948c1a98b5557ac1fc4a385741` | 262144 | `2cbc42f92ac176cca47551ce8246b235095095cb84b3774f0833d653bc2f0eaa` |
| 10 | `volume/fluid_color_z10.webp` | 23674 | `64b44ee259b1975a900aea78d5278bf853e0db5a09e0a9ced07486304cba459e` | 262144 | `992cd41bfcb245150406e1fe808b903274122479f9bea138cf35dcd8bc2226a8` |
| 11 | `volume/fluid_color_z11.webp` | 25216 | `e046fbbb5c1f664bbbab75d1ae577e781a14b7a3e84b13ab140e6d2314f0d62f` | 262144 | `17bd02b6b255ebc16e720a9c54abea8b6e25502c1f81b35e7cd1abf6f0111cb1` |
| 12 | `volume/fluid_color_z12.webp` | 27484 | `763124282eaf6e149054364b6ea3e0e510ff80619fab488ce62aeb6d4e222f8a` | 262144 | `86d0c6c4872e90e0aa57df669b457995ad4c59037fa78a373c74751d747644cb` |
| 13 | `volume/fluid_color_z13.webp` | 29706 | `9b1f8ad537b8d39a025b460f96beb846aef7aaaf4d0cc4568c8f4fb0f8f8d9a7` | 262144 | `8a9baea9c247902071247acabd29a5c3bf18d75dfe5882bd073edc97326efa8c` |
| 14 | `volume/fluid_color_z14.webp` | 31220 | `7dc29dd3f5b622bf9225c471dde56288edb7a82690712531f79ba67b922454c7` | 262144 | `09b027e076316b6be5562b6886e9dc0e667981a0c61f3b34c4533874833a4fb2` |
| 15 | `volume/fluid_color_z15.webp` | 30858 | `9fa0df3c950415a74468cb1e01ca569dbd757bc18b4105b8d4c8ecac221030e9` | 262144 | `7ac1b8da943efc1b8d7a10a7198760517492569564037ef8ada3d71b9520c239` |
| 16 | `volume/fluid_color_z16.webp` | 31410 | `547447d73e40a48112b6900ed79c8c350fa332571bb8491280dd47dfc9132841` | 262144 | `74330f6b7407422611147a6cb17be014fba0136721831207f3298322f326c1d9` |
| 17 | `volume/fluid_color_z17.webp` | 31562 | `fb8874cb36b6dad2ec4ca8947a5524b37b0fd8ac28779efb969a793f6b0c759a` | 262144 | `36c409852a06c6e10af93f0b3c5d5ea90aac24fda99740789981b2f9b56e23e2` |
| 18 | `volume/fluid_color_z18.webp` | 33192 | `d72c156fde525872d7c4adf1f64500bf16a178a53b3558bcb6a2050308d035fd` | 262144 | `36407e484eac6307c4af6982221960e517b7c3cda1f521a2c960d7c7f148141c` |
| 19 | `volume/fluid_color_z19.webp` | 35018 | `57d329fbd514fa8db5cd4f6242ce9942faa4d960ea7e334e2b572c5ac4127a72` | 262144 | `6b01a4215e938a7dfc628180877c9a9a1b29ab9a99bb42434f9ef68584b38890` |
| 20 | `volume/fluid_color_z20.webp` | 36216 | `8549ca934945cf9dca8b471a2f0d36dce58ee665f4424366145adcc07f7489fb` | 262144 | `cbe66e85611db67f50ab82d93c48246f264bec7e480cf19ee6cedb087b426e66` |
| 21 | `volume/fluid_color_z21.webp` | 36682 | `7db20cc5c1713844c38a06781dc60a4cfc7e2ac4f3d4a82b7d3a81f75dc9c8e5` | 262144 | `1db000a85176d7862ba17139ffd7170099cf90033e70755a8d8fc6d2ab31f9aa` |
| 22 | `volume/fluid_color_z22.webp` | 38250 | `0dc499a7f873e2648ad112440212361fb51108ef8e338c1ab37683edcbce6c26` | 262144 | `b4ca9ed58aa19805475d542d5a079da2374a2420d0811fdec7453f87f93e06b3` |
| 23 | `volume/fluid_color_z23.webp` | 38834 | `990f617ca4b17211b69226ca094d334f66da7ea02afd808c98486f114800d812` | 262144 | `62a169e485e29682cabe28cce30c97e2af915d23b18090eac7015e61059b34b1` |
| 24 | `volume/fluid_color_z24.webp` | 37046 | `baf050809fbe56c4e2c31b03d8b3a2929c77d7a8e027a5403ddab2e5f041aefa` | 262144 | `6b17dcbe2200a49110697d9fd3366554f063806850cd7b19773c0c531bf60f06` |
| 25 | `volume/fluid_color_z25.webp` | 32584 | `c0963b2e848ab070ac75e8e6202ab29004acfc66e1710f01767fd45cde449052` | 262144 | `ee5fd544533a16ba90a30bec3fb9451e65b1fcf37454374c84d76d5c8a85e33e` |
| 26 | `volume/fluid_color_z26.webp` | 28634 | `c94a61b6da2df4e2d7e5f192b6a2a1bc2937fc873a70491d6018d0e86625c36f` | 262144 | `72c6e2c450c7896f88002f63126ef94b38cffe462bd4dfe1ba443f400454bbc8` |
| 27 | `volume/fluid_color_z27.webp` | 23196 | `95bfda3135fec3b3ed47e8ec3fdb99d79e539ddeea78f33087ca0c4fdd9c9aa8` | 262144 | `706b5402229423ac71d6317f356454ed88054c4dfbb18ff6d684d00adcb21269` |
| 28 | `volume/fluid_color_z28.webp` | 18582 | `2d82e1fcf40502f139ac62d97f0b59f1961e6f0feeb8f4f4a59a4e8eae16c43c` | 262144 | `f43386eb18e957130ca38c36bd2448a68b3665cd1d4d21e27a8c01bd39cf05da` |
| 29 | `volume/fluid_color_z29.webp` | 12748 | `92784cee073c09bce70863ede80a97056a5ad5cf201492fc56abd5be83a272e0` | 262144 | `4887f5938a7dd797db661067b941cccee65a20f61c4707e28f815514e4b7d8ed` |
| 30 | `volume/fluid_color_z30.webp` | 6482 | `bc3d925a247036a67183ee846d876b57963da936219e0880d39f9a0a3de14bae` | 262144 | `65f1fb2c7d46854e35783471b00c36ce97320f7b0d392f8cad28a8b03f54d280` |
| 31 | `volume/fluid_color_z31.webp` | 1288 | `5a0e20c709bccd1c369ee3899c852469e62171845b14a8d0e7aa959c9a9aaa38` | 262144 | `a4db9be9f431c10a047b25a1a484abce96a195136bd1e9dfd82d0794024e61e6` |

## 5. 内核光学融合

- 渔见 `SPHERE_FS` 的算法要点只用于内核：曲率法线、角度 Fresnel、光谱折射、液面 Crest 和多尺度焦散；未复制整个外围 Overlay。
- Demo 的 Icosphere、sampler3D、旋转后的 3D Volume、curl/domain warp、Crystal Lens SDF、Beer-Lambert 和 HDR 环境反射仍保留。
- 外壳 Vertex 法线使用逆转置 Normal Matrix；Fragment 先按 Master IOR 处理空气/玻璃外界面，再按玻璃/水界面计算第二次 Snell/Fresnel 路径，并使用独立 `glassWallThickness=0.115`。空气、水、晶体、玻璃 IOR 来自 Master：1.0003、1.333、1.45、1.45。
- 体积颜色、密度和法线共用变形后的采样坐标；中心差分生成体积法线。Front-to-Back 积分使用 `density × densityScale × segmentLength`，RGB 按 Master Beer-Lambert 系数独立衰减。
- 焦散在三个不同体积路径深度上采样，并受局部密度、路径吸收及腔体位置约束；没有固定平面贴片。
- Key、Warm Rim、Cool Rim、Emerald Fill 和玻璃/fold optics Uniform 均有运行时消费者。Shader 编译验证覆盖 16 个生产 Shader、9 个链接 Program；12 个核心拟合 Uniform 保持活动。
- `CrystalLensGeometry` 的既有三方几何保留 `ASYM_SHEAR=0.08`；本轮 Canonical closure 测试覆盖 90 条 Lens ray。

## 6. Camera、Capture 与 GPU 状态

Camera 继续使用现有 `OrbCameraSnapshot`/`OrbCameraBuilder`；六种诊断帧共享 Snapshot ID、Camera Config Hash、Transform Hash、Viewport、Core Rotation 和 Motion Phase 的既有 Capture 流程。Stage A 审计测量 square 直径误差约 0.00%、中心误差约 0.01%、轮廓 IoU 0.9996；portrait 直径误差 1.624%。

本次执行没有可确认的真实硬件 GLES 设备，因此没有产生六张真实 GPU PNG、硬件 GPU Telemetry 或可交付 Capture ZIP。运行时 Capture 代码会在可用设备上检查 FBO/GL Error、PNG 尺寸/解码和 SHA；GPU 时间不可采时必须标为 `UNAVAILABLE`。此项保持 `PENDING_REAL_GPU_CAPTURE`，不能据此宣称 GPU_CAPTURE_PASS 或 REAL_DEVICE_VISUAL_ACCEPTED。软件 Mesa GLES 结果只证明 Shader 编译和链接，不是设备渲染证据。

## 7. 自动化验证与构建交付

| 验证 | 状态 | 证据 |
|---|---|---|
| Frozen Master SHA/尺寸 | PASS | `AI_ORB_V3_2_MASTER_LOCK.md`, V3.2 Robolectric test |
| Canonical evaluator + smoothstep | PASS | `test_v32_closure_gates.py` |
| 全量 Lossless WebP RGBA 闭环 | PASS | `CANONICAL_VOLUME_PIXEL_CLOSURE.json` |
| 35/35 资产 Manifest SHA | PASS | `validate_webp_assets.py`, `SHA256SUMS` |
| 独立解码 WebP 17/17 gates | PASS | `FIT_VALIDATION_RESULT.json` |
| 360° 连续性 | PASS | `validate_volume_continuity.py`，loop closure 0 |
| Crystal Lens + negative CI | PASS | 90-ray lens check；10/10 负向测试被拒绝 |
| OpenGL ES 3.0 Shader compile/link | PASS | `validate_glsl_es.py`（Mesa 软件上下文；仅编译证据） |
| Kotlin/Robolectric 单元测试 | PASS | 105 tests；含参数合同、Volume、Capture Snapshot、外围冻结回归 |
| AndroidTest Kotlin 编译 | PASS | `:app:compileDebugAndroidTestKotlin` |
| Lint | PASS | `:app:lintDebug` |
| Debug APK 构建 | PASS | `:app:assembleDebug`；提交后会以精确 HEAD 重建并校验嵌入资产 SHA |
| 六帧真实 GPU PNG / 20 秒设备动态验收 | PENDING_REAL_GPU_CAPTURE | 需真实 Android GLES 3.0 设备 |

GitHub Actions 工作流按 `MASTER_LOCK → WEBP_ASSET_VALIDATION → VOLUME_CONTINUITY → OFFLINE_FIT_VALIDATION → CANONICAL_CLOSURE_TESTS → ALL_UNIT_TESTS → ANDROID_TEST_COMPILE → LINT → DEBUG_APK_BUILD → APK_ASSET_SHA_VERIFICATION → EXACT_HEAD_FINGERPRINT → ARTIFACT_UPLOAD` 顺序运行，CI JDK 固定为 21 以满足 SDK 36 Robolectric。此环境本地通过 JDK 17 编译并让 Test Worker 使用预装的 Java 21 运行时（Robolectric 缓存放在可写目录）；Android 应用仍使用既定 JVM target。精确 HEAD、CI URL、APK SHA/大小及下载链接由 `BUILD_FINGERPRINT.json` 与对应 CI Artifact 填入；不以此前主分支的 APK 代替。

## 8. 关键文件

- 离线门禁：`docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json`
- Master 参数：`app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json`
- 正式资产清单：`app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json`
- 各资产 SHA：`app/src/main/assets/orb_v3/manifest/SHA256SUMS`
- 全量 RGBA 闭环：`tools/orb-v3-generator/fitting/CANONICAL_VOLUME_PIXEL_CLOSURE.json`
- 解码 WebP 独立验证：`tools/orb-v3-generator/fitting/FIT_VALIDATION_RESULT.json`
- Stage A–F 运行记录：`tools/orb-v3-generator/fitting/FIT_RUN_SUMMARY.json`
- WebP 明细：`docs/v3/WEBP_ASSET_REPORT.md`
