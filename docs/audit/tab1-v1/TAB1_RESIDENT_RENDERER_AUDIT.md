# Tab1 Resident Renderer 审计

## 几何架构与五个冻结身份

`AIOrbOrbitRibbons.kt` 使用 3D ribbon surfaces、Catmull–Rom 路径和动态横截面，不是 2D 线段。Descriptor 保存独立控制点、宽度、相位、周期、扭转、深度层、颜色和材质（`:37–63`）。主五身份：

| ID | 深度 | 半宽 | 周期 | 运行职责 |
|---|---|---:|---:|---|
| G01 | BACK | 0.178 | 6100 ms | 绿色 Resident |
| A01 | BACK | 0.092 | 3700 ms | 金色 Resident |
| C01 | FRONT | 0.138 | 4400 ms | 青色 Resident |
| A02 | FRONT | 0.118 | 2900 ms | 金色 Resident |
| G02 | FRONT | 0.195 | 5300 ms | 绿色 Resident |

源码还包含 `SEC_*` 二级细丝 Descriptor；默认 `ribbonCount=5` 才意味着活动的五个 primary identity。后续需要单独记录 active descriptors，不能把额外细丝当成新增 Resident 身份。

## 参考图限制

REF_B 冻结的语义 masks 只有 3 组：`FRONT_CYAN`、`FRONT_GOLD`、`BACK_GREEN_GOLD`。这些不是五身份逐一 mask；`BACK_GREEN_GOLD` 还把多身份合为一组。静态 RGB 图和 mask 无法恢复准确 Z 深度、真实遮挡方向或时间周期。ROI mask 内有重叠，不能用像素语义猜测独立轨迹。

## GPU/机器证据

- `mode_05_RESIDENTS_ONLY.png` 中青色多股宽带在 Orb 前下部横穿，外围有宽绿弧和金色曲线。
- REF_B 冻结 mask 的 Edge F1@6px：后景绿/金组 **0.1550**，前景青 **0.1219**，前景金 **0.1313**，目标 ≥0.85。
- 这些指标说明当前边缘在冻结区域中有明显错位/多余边缘；群组 mask 不能证明每个身份独立轨迹谁错。
- `RIBBONS_ONLY` 复用 `RESIDENTS_ONLY` PNG 的 SHA（`06a912…5cbb8`）。Capture manifest 证明两个模式选择同一 image SHA；因此它们不是两组独立的视觉样本，后续应检查诊断语义是否设计为相同预期。

## 绘制与遮挡

Tab1 活动 FULL_RENDER 顺序是 Back Resident → Refraction Snapshot → Core → Front Resident。`drawPass(0/1)` 各按深度角色绘制。Back Resident 进入 Core 可采样的 FBO 背景；Front Resident 在 Core 之后再画，直接叠到 Core 前方。整体场景排序存在，但 Front 层不再次经过 Core 的折射/吸收/曝光响应。

**审核结论**：源码证明空间 pass 明确分了前/后，但候选实际宽度/轨迹与冻结语义区域差距很大；来源有 Edge F1 数值和图像支持。因为 masks 没有五条独立路径，最终逐条轨迹/身份重拟合仍需要新的用户签字轨迹定义或可测量视频，不能仅凭静态图确认每个 descriptor 的对应关系。
