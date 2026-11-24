# Tab1 下一轮重构执行计划

优先顺序按根因依赖，而非按视觉参数数量排序。每阶段继续只用冻结 REF_A/REF_B、mask、阈值与 1254×1254 坐标；新算法输出不写回旧历史报告。

| 阶段 | 工作 | 退出门槛/证据 |
|---|---|---|
| 0 输入合同 | 核对 REF_A/REF_B、Contract SHA、12 masks；显式写明 REF_A isolated-core 与 REF_B full-scene 的 viewport / object scope | SHA、画布和 ROI 冻结；不改母版/mask |
| 1 生产路径 | 回归 shader/source SHA、uVolumeLoaded、4 textures、格式、Uniform readback、context loss/reload、manifest/实际 pass 一致 | production high-fidelity frame 无 fallback；GLError=0；pass manifest 真值 |
| 2 Geometry/Cavity/Lens | 同 camera capture core silhouette、cavity-only、lens depth/normal；减少闭合 lens 视觉结构并保留嵌入晶体 | REF_A geometry gates 和 frozen lens ROI 通过；不能隐藏整层 |
| 3 Volume/Material | 使用真实 WebP 做同坐标体素/Raymarch检查；分开 density、RGB吸收、sRGB、normal、fold/flow；先 Emerald/Sapphire/Aqua | 区域面积误差≤15%、质心≤0.04D；core ROI SSIM达到contract |
| 4 Glass & light | 单独验证外壁/内壁/背景传输/内部液体能量；Bloom-off 首先满足结构可读性 | 不出现中心白雾；光强/clip 与真实界面对应 |
| 5 Resident | 五身份分层 debug；校验宽度/路径/边缘；明确 mask 只能映射到三个 semantic groups | 每 frozen Resident group Edge F1≥0.85；无一条青带夺占Core |
| 6 全场量化 | 同一锁定 Camera/Motion/Viewport，捕获 REF_B 全部 regions；计算 SSIM、ΔE、轮廓、面积/质心，不作配准 | 全场 ≥0.82，median ΔE≤8，P90≤15；其他gate同步PASS |
| 7 AVD重复性 | 3次 cold-start 完整导出；记录图像和运行参数哈希；必要时多运行超过三倍噪声才认改善 | repeatability PASS，PF04视觉门槛PASS |
| 8 硬件与真机 | 真实 GLES GPU/用户目标设备运行同一构建；20秒动态与 FPS/GPU timing/热态 | 标识设备/driver/时序；Hardware GPU verified；用户人工验收 |
| 9 Exact HEAD 发布 | 全测、APK 指纹与 Git SHA 对齐、Evidence zip/SHA manifest | 一个 Exact-HEAD CI/artifact；不把 SwiftShader标为硬件 |

## 每轮需要记录

基线 HEAD、shader/master/asset SHA，实际 uniform，运行后端，camera/motion snapshot，图像 SHA，逐项指标，图像和代码变更，CI 结果及设备证据。任何未测项必须 `NOT_RUN`/`BLOCKED`；视觉优化超过 repeatability 的三倍噪声再采纳。不得先调整全局颜色/Bloom去“盖”结构问题。
