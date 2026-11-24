# Tab1 架构与视觉根因审计摘要

**任务**：`AI_ORB_TAB1_ARCHITECTURE_VISUAL_ROOT_CAUSE_AUDIT_V1`
**审计结论状态**：`BLOCKED_WITH_EXACT_EVIDENCE`
**代码基线**：`fb04598709a282d3818745c07b0a71897cccfd66`（`codex/tab3-refb-outer-optical-field-v1`）
**审计工作分支**：`codex/tab1-architecture-visual-audit-v1`
**变更范围**：只新增本目录审计文档、量化文件和取证图；未修改生产代码、Shader、资产、参数、mask、门槛或 UI。

## 结论

1. **Tab1 正式路径确实在运行原生生产 Renderer 和 3D WebP Volume。** Exact-HEAD API36 capture 证明生产 Shader Program ID 为 6，Shader SHA 为 `4fc2be7e…bc26014`，`uVolumeLoaded=1`，256×256×32 `GL_SRGB8_ALPHA8` Volume 与四个有效纹理已绑定，Uniform readback 有效，FBO 完整，`GL_NO_ERROR`。在正式高保真模式中，资产未就绪时显示启动背景并报告状态；没有证据表明这次 capture 静默退到程序化 Volume。
2. **视觉验收失败，且重复捕获稳定复现。** REF_B 全场 SSIM 为 **0.4628**（门槛 ≥0.82）；ΔE2000 中位数 **13.04**（≤8），P90 **36.20**（≤15）。冻结的三次冷启动输出哈希一致，因此失败不是本次像素噪声。
3. **主要材质区的偏差有机器证据。** Emerald 面积比目标多 127.55%；Sapphire 面积少 44.23%；Aqua 面积少 99.15%，且 Sapphire/Aqua 质心超限。以上按 REF_A 的隔离 Core ROI 与冻结 P0-3 分类口径，不能与 REF_B 的全场 SSIM 混为一项指标。
4. **顶部 Lens 是明确的结构性差异。** `CAVITY_ONLY` GPU capture 中仍见闭合的蓝色椭圆；Shader 以独立 Lens SDF 求交、Pocket Mask、清背景透射和局部 Lens Rim 合成它。因而它不是 Resident 或 Bloom 叠加造成的假影。需要重构 Lens 与 cavity 的几何/遮挡关系，不能仅隐藏 Lens。
5. **Resident 的路径/宽度和合成优先级均有量化问题。** 冻结 ROI Edge F1 约 0.12–0.16，显著低于 0.85；捕获图中前景青色带与宽绿带跨越 Core。源码确认后景 Resident 在 Core 前被拍入折射快照，前景 Resident 在 Core 后直接绘制；不是双向的统一光学合成。
6. **不能声称硬件 GPU 或真机性能通过。** API36 使用 Google SwiftShader 软件 GLES；不是物理 GPU。三轮 repeatability PASS。Capture 阶段计时被模式切换和读回扰动，不可作稳态 Tab1 FPS 基线；GPU 时间不可用。真机/硬件视觉验收仍待完成。

## 关键状态

| 检查 | 结果 | 证据含义 |
|---|---|---|
| 冻结 Contract、REF_A/REF_B 与 12 个 ROI mask 的 SHA/尺寸 | PASS | 输入身份一致；未修改 |
| P0-3 PF01 / PF02 / PF03 / PF05 | PASS | ROI、Raymarch readback、几何、三轮重复性 |
| P0-3 PF04 API36 视觉基线 | FAIL | 全场与 Core 区域不满足视觉门槛 |
| GPU 后端 | SOFTWARE_RENDER | SwiftShader；不能写成 Hardware GPU |
| 生产 3D Volume 加载 | PASS（本次 capture） | `LOADED`, 4 个 texture object 有效，uVolumeLoaded=1 |
| REF_B 全场 Fidelity | FAIL | SSIM / ΔE2000 均超阈值 |
| Tab1 真机持续性能与人工视觉验收 | NOT_RUN / BLOCKED | 没有物理设备证据 |
| 生产代码修改 | 未执行 | 本任务是审计与方案冻结 |

## 最短审阅入口

- [视觉结构与对照](REF_B_VISUAL_STRUCTURE_ANALYSIS.md)
- [根因矩阵](TAB1_ROOT_CAUSE_MATRIX.csv)
- [架构调用图](TAB1_ARCHITECTURE_MAP.md) 与 [渲染调用链](TAB1_RENDER_CALL_GRAPH.md)
- [目标架构](TAB1_TARGET_ARCHITECTURE.md) 与 [重构执行计划](TAB1_REFACTOR_EXECUTION_PLAN.md)
- [量化结果](TAB1_METRICS.json)、[性能基线](TAB1_PERFORMANCE_BASELINE.json)
- [证据清单](TAB1_EVIDENCE_MANIFEST.json)、[GPU 证据 ZIP](evidence/AI_ORB_TAB1_GPU_EVIDENCE.zip)
- [REF_B 与 FULL_RENDER 原画布并排](TAB1_REF_B_VS_GPU.png)、[冻结 Resident ROI 叠加](TAB1_FROZEN_REF_B_ROI_OVERLAY.png)、[像素差异图](TAB1_PIXELWISE_DIFF_X2_5.png)

## 状态解释

本审计已完成可由冻结 Exact-HEAD 软件 AVD 证据支持的源码路径核对、视觉差异量化和根因排序。状态仍为 `BLOCKED_WITH_EXACT_EVIDENCE`，因为本轮没有真实硬件 GPU/用户目标设备的持续帧率、设备捕获与人工验收证据。它不代表源码修复、GPU Fidelity 或视觉接受通过。
