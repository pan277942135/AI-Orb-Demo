# Tab1 Core Renderer 审计

## 已验证的正式执行路径

- `AIOrbCore` 是 V3 Core shell 与液态 Volume 的入口，只持有/委托一个 `OrbFluidVolumeRenderer`（`scene/AIOrbCore.kt:8–16`）。
- `OrbFluidVolumeRenderer.init` 编译 `CoreFluidShaders.CORE_GLASS_VERT/FRAG`（`scene/OrbFluidVolumeRenderer.kt:81–92`）；draw 中绑定 Refraction FBO、体积纹理和 Master uniform，画 Icosphere（`135–165`, `218–220`）。
- `AIOrbInnerFluid.kt` 在当前 AIOrbScene Tab1 调用链中未发现调用点；不能把历史类存在误认为第二套水体正在叠画。
- 本次 Exact-HEAD capture 读取到 Program ID、源码 SHA、GL texture IDs、维度、格式与 uniform。加载失败静默走备用体积的解释不符合本次 runtime evidence。

## 资产与色彩空间

- `OrbVolumeTexture3D` 使用状态 `UNINITIALIZED / LOADING / LOADED / V3_ASSET_ERROR / DIAGNOSTIC_PROCEDURAL_FALLBACK`（`v3/OrbVolumeTexture3D.kt:16–22`）。异步解析 35 张 lossless RGBA WebP，GL 线程再上传（`94–125`, `137–184`）。
- 本次 runtime 的 Volume 是 `GL_SRGB8_ALPHA8`, `volumeIsSrgb=true`；Shader 仅在线性 `RGBA8` fallback 时手动 gamma decode（`CoreFluidShaders.kt:711–720`）。没有证据支持“当前 capture 发生双重 gamma 解码”。
- 从仓库当前 32 张 lossless WebP 解码的 Alpha 统计（256×256×32，全量 2,097,152 voxels）：P10=0、P50=0、P75=0.05490、P90=0.29804、P95=0.42745；Alpha>0.14 占 21.4994%，Alpha=0 占 53.8798%。这是资产级体素统计，不是可见 raymarch 激活率。
- Shader 当前 Fold 采用连续 `densityEdge × gradientEdge` 权重，`localDensity > 0.035` 是昂贵法线计算的采样门槛（`CoreFluidShaders.kt:1031–1054`）；并非旧版 `>0.14` 二元高光门槛。本次 Fold diagnostic 中可见液体的平均激活比为 0.413。不能据此推论物理设备每角度均有相同覆盖。

## Root 与空间/材质

- 体积采样执行旋涡/curl 域变形后再从 sRGB WebP 采样，并输出同一变形采样点（`CoreFluidShaders.kt:695–746`）。Raymarch 正向从前往后累加，Beer–Lambert 按 RGB 独立 `exp(-uBeerLambert * sigma * segment)` 吸收（`1016–1074`）。
- Shader 中有深色 Sapphire/透明空隙/局部 Aqua/Emerald 的分支，代码存在不等于结果符合母版。全场 AVD 指标确认 Emerald 过量、Sapphire/Aqua 不足；需对 Volume 采样、cavity clipping、吸收和最终合成做逐项可视化验证。
- Crystal Lens SDF 在 Core Frame 单独求交（`945–950`），再有 Lens pocket mask 与透射/Rim 混合（`1208–1225`）。同一椭圆在 `CAVITY_ONLY` 诊断图中清晰可见，故它是可见几何/遮挡结构，不是只有 Bloom 才出现的轮廓。
- 外壳片元最终在同一 Shader 中与背景 `mix(originalBg, col, edgeAlpha)` 后输出 alpha=1（`1301–1304`）。因此它是 Shader 内完成的一次整体复合，并非供 GL framebuffer 做透明分层混合的玻璃 alpha 层。

## 明确未证实项

- 没有物理 GPU 的 raymarch step 数、Texture filtering 或驱动差异证据。
- 没有 context loss 自动重载回归结果；源码有 Context invalidation/reinit 路径，capture 只验证一次正常 load。
- `FOLD_NORMAL_GPU.png` 的诊断色不能直接当作成品材质颜色。
- 没有从当前硬件执行各 RGB 色彩通道断言；这里只验证 runtime sRGB flags 和源码互斥分支。
