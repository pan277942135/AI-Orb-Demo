# Tab1 光学与合成管线审计

## 现有顺序

`AIOrbScene.renderOriginalOrbOnlyFrame`：背景/远景 → Back Resident/flare → blit 折射快照 → Core Glass + Volume 作为一个 fragment pass → Front Resident/flare/粒子 → Post。`AIOrbPostProcessing.captureRefractionSnapshot()` 只把 Scene FBO 颜色 blit 到无深度 Refraction FBO；Core Shader 取样这个单一快照。

## 已确认的结构事实

1. **单向、非对称的 Resident 交互。** 后景在快照中并可被 Core 折射；前景在 Core 之后画，不被 Core 透射/遮挡。本次 FULL_RENDER 有非常明显的前景青白带横穿内核，Resident edge F1 也失败。
2. **外玻璃在 Core shader 中与 Refraction 合并。** `CORE_GLASS_FRAG` 内有双界面 IOR/refraction、Fresnel、Environment 和最终 background mix；输出 `alpha=1`。不是若干独立透明玻璃层通过标准 alpha blend 叠起来。
3. **Bloom 位于 scene rendering 之后。** 后处理关闭 Depth/Blend，再做亮部提取、blur、Composite/ACES/sRGB。 `BLOOM_OFF` capture 与本次 FULL_RENDER 像素 hash 一样（两者 SHA 可见 `BUILD_FINGERPRINT.json`），这说明该时刻 Bloom 没改变输出；然而内核绿色雾感和 Resident 结构仍然存在。不能把本次失败归因于 Bloom 参数或声称关 Bloom 已恢复内核。
4. **Lens/Cavity 直接参与颜色复合。** Lens pocket 会将内部颜色 mix 到折射背景，并累加 Fresnel Rim；CAVITY_ONLY 显示完整独立椭圆，所以 Lens 是核心 root-cause 项。
5. **光照链分 Shader。** Core `AIOrbLighting.applyTo` 提供方向/颜色；Ribbon Shader 有独立 palette/材质与 blend state。不存在同一能量/遮挡方程覆盖核心及 Ribbon 的统一 optical field。

## 建议冻结的重构目标

- 继续单一 Scene/Renderer 和 3D Volume；重构具体 Core surface/volume 与 composition graph，不复制第二套 Core。
- 先让 glass/inner wall/liquid/caustic 各自可 capture，再合并；同一个世界空间、线性 HDR 约定、可追踪深度和遮挡标签。
- 折射读取应明确前景/后景层；对 Front Resident 使用折射层遮挡决策，而非仅在最后叠加全亮表面。
- Glass 与 liquid 应各自贡献透射/吸收/反射一次；记录能量预算和 HDR clip，避免多个子层重复加亮。
- Bloom 仅负责最后已有亮点的扩散；BLOOM_OFF 作为材质可读性的必要门禁。
- 实施前在同一 snapshot 下逐层 A/B，视觉阈值与 mask 冻结不变。
