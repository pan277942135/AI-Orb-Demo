# AI Studio Work P0
# Five Resident Filaments V1｜五条外围光丝动态实现与验收指令

**Task ID:** AISTUDIO_FIVE_RESIDENT_FILAMENTS_V1_IMPLEMENTATION  
**Project:** AI Orb Demo / Water World V2.1 / Tab 3  
**Priority:** P0  
**Implementation scope:** G01 / G02 / A01 / A02 / C01 only  
**Frozen design contract:** docs/water-world-v2-v21/FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1.md  
**Contract branch:** docs/five-resident-filaments-v1  
**Contract baseline Main HEAD:** 120aeb2c1f716771351bf951826539350bedb512  
**Master SHA-256:** fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589  
**Implementation branch target:** feat/five-resident-filaments-v1  
**Execution mode:** Existing-project targeted implementation; no architecture rewrite.

---

# 0. 任务目标

严格按照 Five Resident Filaments V1 设计冻结合同，对现有五条外围 Resident 光丝实施动态升级。

唯一目标：

在不改变五条光丝静态几何、身份、颜色、材质、布局和前后景分段的前提下，实现真正可感知的：

1. 沿路径的连续流动。
2. 五条光丝不同方向、不同速度、不同相位。
3. 沿路径的局部横向波动。
4. 基于真实局部宽度的宽度呼吸。
5. 独立且柔和的亮度呼吸。
6. 同一 Resident 的所有 Render Segments 使用统一动态状态。
7. 60 秒连续循环、暂停恢复和 T=0 静态回归。

本 Work 不是重做 Water World，不是重做 AI Orb Core，也不是重新绘制五条光丝。

**必须先审计、建立几何锁定和 Segment 坐标映射，再修改 Profile、Evaluator 和 Shader。禁止跳过审计直接调 Shader 参数。**

---

# 1. 首要输入：先读取冻结合同

开始执行前，必须从 GitHub 读取：

- 分支：docs/five-resident-filaments-v1
- 文件：docs/water-world-v2-v21/FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1.md

该合同是本 Work 唯一的 Resident 产品设计输入。

如果当前 AI Studio 环境无法读取上述分支：

- 停止代码修改。
- 不允许凭本指令摘要替代完整合同。
- 报告 CONTRACT_SOURCE_UNAVAILABLE。
- 明确指出无法读取的文件路径和权限/分支问题。

不得修改、重写或覆盖冻结合同。

---

# 2. Git 与源码准入

## 2.1 分支约束

1. 重新获取 GitHub Main 最新 HEAD。
2. 核对仓库为 pan277942135/AI-Orb-Demo。
3. 不允许直接在 Main 修改代码。
4. 创建独立实现分支 feat/five-resident-filaments-v1。
5. 实现分支必须从当时最新 Main 创建，不得从旧工作区或不明 Commit 创建。
6. 不允许 Force Push。
7. 不允许覆盖其他分支提交。
8. 不允许修改或删除本 Work 范围外的已有提交。

如果 Main 与合同记录的基线 120aeb2c1f716771351bf951826539350bedb512 不一致：

- 先审计 Main 新增的相关改动。
- 确认 Water World V2.1 Work 3 核心文件和五条 Resident 资源未发生冲突。
- 生成 BASELINE_DIFF_REPORT。
- 不得静默回退到旧 HEAD。
- 不得覆盖 Main 新增实现。

## 2.2 必须先审计的源文件

Android Runtime：

- app/src/main/java/com/example/waterworld/WaterWorldMotionProfile.kt
- app/src/main/java/com/example/waterworld/WaterWorldMotionEvaluator.kt
- app/src/main/java/com/example/waterworld/WaterWorldMotionRuntime.kt
- app/src/main/java/com/example/waterworld/WaterWorldMotionTimeline.kt
- app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt
- app/src/main/java/com/example/waterworld/WaterWorldShaders.kt
- WaterWorldDynamicFrameSnapshot 的实际定义文件。
- Resident Mesh 的实际加载、解析和 Draw Binding 路径。

Motion Profile：

- releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json
- releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/WORK3_MOTION_PROFILE.json

Geometry / Scene：

- releases/water-world-v2-v21/delivery/master_resident_geometry.json
- releases/water-world-v2-v21/delivery/resident_centerlines.json
- releases/water-world-v2-v21/delivery/resident_identity_map.json
- releases/water-world-v2-v21/delivery/resident_occlusion_map.json
- releases/water-world-v2-v21/delivery/resident_width_profiles.json
- releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/03_SCENE_GRAPH/draw_bindings.json
- releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/02_SUPPLEMENTED_ASSETS/geometry/

Existing tests：

- app/src/test/java/com/example/WaterWorldMotionEvaluatorTest.kt
- app/src/test/java/com/example/WaterWorldMotionRuntimeTest.kt
- app/src/test/java/com/example/WaterWorldMotionTimelineTest.kt
- app/src/test/java/com/example/WaterWorldDynamicDeterminismTest.kt
- app/src/test/java/com/example/WaterWorldDynamicPlaybackLifecycleTest.kt
- app/src/test/java/com/example/WaterWorldDynamicRendererTest.kt
- app/src/test/java/com/example/WaterWorldShaderUniformContractTest.kt
- app/src/test/java/com/example/WaterWorldThirdTabIntegrationTest.kt

先确认实际文件和调用关系，不得根据文件名推测实现。

## 2.3 当前已知 Runtime 基线

当前工程已有：

- 单一 WaterWorldMotionTimeline。
- WaterWorldMotionEvaluator。
- 每帧 WaterWorldDynamicFrameSnapshot。
- 五条 Resident Profile。
- Back / Front 两个 Resident Render Pass。
- Resident Ribbon Vertex / Fragment Shader。
- uFlowOffsetU。
- uBrightnessScale。
- uDeformMode = 3。
- uDeformParams。

当前 Resident Evaluator 已计算 flowOffsetU、widthPulseScale、brightnessScale、lateralWavePx 和 wavePhaseRad。

当前 Renderer 已按 Resident Group Key 取得状态，再为对应 Draw Segment 上传 Uniform。

当前 Shader 已有沿 U 方向的纹理流动、横向波动和宽度变化入口。

**因此，本 Work 必须在现有链路上修正，不得另建第二套 Motion Runtime、Timeline、Renderer 或 Shader 管线。**

---

# 3. 阶段 A｜源码审计与静态几何锁定

本阶段只读，不修改生产代码。

## 3.1 审计 13 个 Segment

必须逐个核对：

G01：
- G01_back_arch
- G01_back_outer_veil
- G01_front_lower_sweep

G02：
- G02_back_upper_arc
- G02_back_upper_hairline
- G02_back_right_rim
- G02_front_right_embrace

A01：
- A01_back_upper_left
- A01_front_left_embrace

A02：
- A02_back_lower_halo
- A02_front_lower_strand

C01：
- C01_back_lower_thread
- C01_front_lower_right

每个 Segment 必须提取：

- 实际 Mesh 文件路径。
- Draw ID。
- Parent Resident ID。
- Render Pass。
- SHA-256。
- 文件字节数。
- 顶点数。
- 索引数。
- UV U/V 范围。
- 顶点 Position / Normal / UV 格式。
- Mesh Bounding Box。
- U 方向的真实空间走向。
- 横截面方向。
- Segment 起止点。
- 当前几何映射置信度。

输出：

five_resident_geometry_lock.json

必须保存 13 个 Segment 的完整锁定记录。

## 3.2 静态几何零修改

实施前后必须比较：

- 13 个 Mesh 的 SHA-256。
- Draw Binding。
- Render Pass。
- 模型矩阵。
- 五条 Resident 的颜色与材质。
- 原始静态 T=0 输出。

硬性要求：

13 个冻结 Mesh 的 SHA-256 全部一致。

如果某个 Mesh 文件被修改，直接判定 STATIC_GEOMETRY_LOCK_FAILED。

不允许通过重新生成 Mesh 后声称视觉上差不多。

## 3.3 证据不确定性处理

当前身份映射、宽度和遮挡存在 Ground Truth 不确定性。

必须保留：

- IDENTITY_UNRESOLVED。
- UNRESOLVED_IDENTITY_AND_OPTICAL_EDGE_AMBIGUITY。
- UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION。
- UNCLASSIFIED_NOT_ASSUMED_OCCLUDED。

不得通过代码默认值将这些状态改成 PASS。

阶段 A 输出：

- five_resident_source_audit.md
- five_resident_geometry_lock.json
- FIVE_RESIDENT_SOURCE_SHA256SUMS.txt

完成阶段 A 后再进入阶段 B。

---

# 4. 阶段 B｜建立真实 Segment 坐标映射

这是本 Work 最重要的基础环节之一。

## 4.1 先解决 UV 方向问题

当前 Shader 使用 vUv.x 和 uFlowOffsetU。

不得假设所有 Mesh 的 U=0 → U=1 都代表相同的空间流动方向。

必须根据每个 Mesh 的：

- Vertex Position。
- UV。
- Index。
- 局部切线。
- 局部横截面。
- 实际中心线采样。

确定：

- U 增加方向对应的真实空间方向。
- 是否需要 localUvDirection = +1 / -1。
- UV 是否发生翻转。
- 是否存在局部 UV 不连续。
- 是否存在重复、退化或不可靠顶点。

生成显式映射，不允许在 Shader 内根据 Draw ID 猜方向。

## 4.2 建立规范化路径坐标

每条 Resident 使用自己的规范化弧长坐标：

s ∈ [0,1]

每个 Segment 必须输出：

- parentResidentId
- segmentId
- renderPass
- segmentArcLengthStart
- segmentArcLengthEnd
- localUvDirection
- localFlowPhaseOffset
- mappingConfidence
- continuityStatus

映射必须由真实几何和 UV 数据计算得到。

禁止：

- 根据 Segment 名称猜测顺序。
- 根据 Render Pass 推断真实路径先后。
- 用平均位置猜测连接点。
- 将不连续中心线中的 null 点插值成真实路径。
- 将不同颜色或身份不确定的线自动合并。

## 4.3 连接关系不确定时的处理

如果两个 Segment 的连接关系无法通过现有证据确认：

- 不得创建虚构连接曲线。
- 不得为其分配伪造的全局弧长连续区间。
- 两个 Segment 仍可共享同一 Resident 的时间、方向、周期和脉动参数。
- 每个 Segment 可以拥有独立的局部坐标。
- 跨 Segment 连续性标记为 UNRESOLVED。
- 不允许把局部运动连续误报为跨 Segment 几何连续。

必须分别给出：

1. Segment 内部流动是否连续。
2. 同一 Resident 的多 Segment 时间状态是否统一。
3. 跨 Segment 几何连续是否得到证实。

三者不能合并成一个 PASS。

输出：

- five_resident_segment_mapping.json
- five_resident_segment_mapping_audit.md

阶段 B 未通过，不得进入跨 Segment 连续性实现。

---

# 5. 阶段 C｜更新 Resident Motion Profile

只更新五条 Resident Profile。

不修改鱼、Core、水草、岩石、气泡、水体和玻璃参数。

将现有 Work 3 Resident Profile 更新为 V1 参数：

| Resident | Flow Direction | cycle_period_sec | phase_offset_rad | width_amp | width_hz | brightness_amp | brightness_hz | lateral_wave_px |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| G01 | +1 | 16.0 | 0.00 | 0.060 | 0.19 | 0.090 | 0.19 | 8.0 |
| G02 | -1 | 18.0 | 1.24 | 0.050 | 0.18 | 0.080 | 0.16 | 7.0 |
| A01 | +1 | 20.0 | 2.41 | 0.070 | 0.21 | 0.100 | 0.22 | 9.0 |
| A02 | -1 | 14.0 | 3.85 | 0.060 | 0.25 | 0.090 | 0.21 | 8.0 |
| C01 | +1 | 22.0 | 5.02 | 0.045 | 0.17 | 0.070 | 0.15 | 6.5 |

单位：

- cycle_period_sec：秒。
- phase_offset_rad：弧度。
- width_amp / brightness_amp：相对振幅。
- width_hz / brightness_hz：Hz。
- lateral_wave_px：1254 × 1254 Master Canvas 像素。

字段语义：

cycle_period_sec 在 V1 中表示 Flow Traversal Period，即主流光完成一次规范化路径行程的时间。

motion profile 必须记录：

- design_contract_id = WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1
- resident_parameter_provenance = FIVE_RESIDENT_FILAMENTS_V1_DESIGN_TARGET
- water_world_motion_values_validated = false

不得将设计参数标记为测量真值。

兼容性要求：

- 保持 Work 3 Profile 的其他元素和字段不变。
- 保持 root-level Work 3 provenance 的真实含义。
- 更新 WaterWorldMotionProfile.validate()，允许 Resident 使用新的独立 provenance。
- 不得将全局 waterWorldMotionValuesValidated 改成 true。
- 不得改变 47 Elements / 28 Dependencies。
- 不得改变 Core 32 Spatial Z-Slices。

输出：

- 更新后的 work3_motion_profile.json。
- five_resident_motion_profile_v1.json。
- Profile Schema / Validation 测试。

---

# 6. 阶段 D｜修正 Motion Evaluator

修改现有 WaterWorldMotionEvaluator.evaluateResident()。

必须保留：

- 单一 globalTimeSeconds。
- 同一帧不可变 Snapshot。
- 确定性绝对时间求值。
- T=0 静态模式。
- Pause / Resume。
- Speed Scale。
- Delta Clamp。

## 6.1 Flow Motion

每条 Resident 根据独立周期和方向计算流动相位。

逻辑要求：

- direction = +1 / -1。
- traversalPeriod = 对应 Profile 的 cycle_period_sec。
- Flow Offset 必须基于真实 Segment UV 方向映射。
- Segment 的局部 UV 方向不能被忽略。
- Flow Offset 必须连续，不得依赖帧累计。
- 任何时间点均可由绝对时间确定性计算。
- T=0 Static Reference 下 Flow Offset 必须保持静态基准。

不得将所有 Segment 的局部 UV 默认视为同一方向。

## 6.2 Lateral Wave

横向波动必须依据：

- 局部弧长坐标 s。
- 当前时间 t。
- Resident 独立 phaseOffset。
- 冻结 lateral_wave_px。

建议采用多谐波柔性波形，但必须满足：

- 沿路径连续。
- 不同位置存在空间相位差。
- 不能产生统一平移。
- 不能产生大幅蛇形扭曲。
- 端点约束不得制造明显变形折点。
- T=0 时位移为 0。

## 6.3 Width Pulse

必须根据：

- width_pulse_amp。
- width_pulse_hz。
- phaseOffset。
- 局部弧长坐标。

计算平滑的局部宽度缩放。

禁止使用固定像素位移冒充局部宽度缩放。

## 6.4 Brightness Pulse

必须根据：

- brightness_pulse_amp。
- brightness_pulse_hz。
- phaseOffset。
- 局部弧长坐标。

计算独立亮度变化。

要求：

- 有限范围。
- 无负亮度。
- 无 NaN / Infinity。
- 无整条光丝爆闪。
- 同一 Resident 所有 Segment 共享一致亮度状态。

## 6.5 Snapshot

所有五条 Resident 状态必须在同一个 WaterWorldDynamicFrameSnapshot 内完成。

不允许 Renderer 再次独立计算运动参数。

输出：

- five_resident_evaluator_audit.md
- five_resident_deterministic_samples.json

---

# 7. 阶段 E｜修正 Resident GPU Shader

目标文件：

app/src/main/java/com/example/waterworld/WaterWorldShaders.kt

并检查：

app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt

## 7.1 现有问题必须定向核实

当前 Resident Shader 已存在：

- uFlowOffsetU。
- uBrightnessScale。
- uDeformMode = 3。
- uDeformParams。
- uFlow = u - uFlowOffsetU * 2.2。
- 横向波动。
- widthPulse。

必须核实：

1. 流动是否真的表现为沿路径移动的高光，而不是只有静态条纹的相位变化。
2. 所有 Segment 的 U 方向是否正确。
3. Segment 局部 UV 从 0 重新开始是否造成运动相位跳变。
4. 当前 widthPulse 是否使用固定 18px 位移，导致不同宽度光丝变化不真实。
5. 横向波动是否基于局部横截面方向。
6. Fragment Shader 是否存在流光循环边界闪断。
7. Uniform 是否每个 Draw Segment 都正确上传。
8. Back / Front 两个 Pass 是否使用相同 Resident 状态。

不得只检查 Shader 字符串，必须核实 GPU 实际结果。

## 7.2 沿路径移动高光

实现至少一组清晰可见的动态主流光带。

建议结构：

- 主流光带：沿规范化弧长坐标移动。
- 主流光带峰值相对局部基准亮度约 +12%。
- 主流光带宽度约为路径长度的 4.5%。
- 渐隐尾部约为路径长度的 12%。
- 次级细流纹理保持低对比度。
- 使用平滑函数处理前缘、尾部和端点。

主流光必须体现真实方向和速度。

不能用整条光丝的全局亮度脉冲代替移动流光。

Static T=0：

- 动态高光强度为 0。
- 不改变 Work 2 原始静态材质。
- 不改变原始颜色和透明度。

## 7.3 局部宽度计算

当前代码中如果仍然使用固定 18px 作为宽度变化位移，必须移除该固定宽度假设。

必须先分析实际 Mesh 的：

- Vertex Layout。
- UV。
- Index。
- 两侧边缘顶点。
- 局部横截面宽度。

优先从冻结 Mesh 派生每个顶点的局部 halfWidthPx 或等价局部宽度属性。

可以增加运行时派生 Vertex Attribute，但：

- 不允许修改冻结 Mesh 文件。
- 不允许改变原始几何位置。
- 派生数据必须可重复计算。
- 必须有自动测试验证局部宽度计算。
- 无法可靠匹配横截面时必须标记 UNRESOLVED，不得猜测。

宽度形变必须沿局部横截面方向执行：

localWidth = measuredLocalWidth × widthScale

不得将屏幕 X/Y 方向当成所有曲线的统一宽度方向。

## 7.4 横向波动

使用局部切线和法线定义波动方向。

要求：

- 波动沿路径连续。
- 不能把屏幕 Y 方向固定作为波动方向。
- 不同位置的波形相位平滑变化。
- 端点和连接处无尖锐折点。
- 最大位移遵守各 Resident V1 参数。
- T=0 位移严格为 0。

## 7.5 GPU Render Integration

Renderer 必须对每个 Segment：

1. 正确解析 Resident Group Key。
2. 获取同一帧 Snapshot 中对应 Resident State。
3. 上传统一的 Flow / Width / Brightness / Wave Uniform。
4. 使用该 Segment 的 UV Coordinate Mapping。
5. 保持原有 Model Matrix。
6. 保持原有 Render Pass。
7. 保持原有 Blend / Depth 状态。

禁止在 Render Loop 中创建额外 Timer、分配大量临时对象或重新构建 Mesh。

输出：

- five_resident_shader_audit.md
- five_resident_gpu_uniform_audit.json

---

# 8. 阶段 F｜自动化测试

必须新增或扩展现有测试，不得删除既有测试。

## 8.1 Geometry Lock Tests

验证：

- 13 个 Segment ID 完整。
- 5 个 Resident 身份完整。
- 8 Back + 5 Front。
- Mesh SHA-256 不变。
- Draw Binding 不变。
- Render Pass 不变。
- 无新增或删除 Segment。

## 8.2 Profile Tests

验证：

- 5 个 Resident Profile。
- 五个周期不同。
- 五条流向符合合同。
- 参数与合同逐项一致。
- 每条 Resident 的振幅和频率符合合同。
- Design Provenance 正确。
- waterWorldMotionValuesValidated 仍为 false。
- 其他 42 个 Canonical Elements 不受影响。

## 8.3 Evaluator Tests

必须覆盖：

- T=0 静态状态。
- T=1 / 2 / 4 / 8 / 12 / 20 / 40 / 60 确定性求值。
- 正向和反向流动。
- 五条 Resident 不同速度。
- 五条 Resident 不同相位。
- 宽度变化达到目标范围。
- 亮度变化达到目标范围。
- 横向波动达到目标范围。
- 所有输出均为有限数值。
- 不同帧率下相同绝对时间得到相同状态。
- 同一 Resident 的多个 Segment 共享同一动态状态。
- 未证实连接关系不会被自动插值。

## 8.4 Shader / Renderer Tests

验证：

- Vertex / Fragment Shader 编译。
- 所有新增 Uniform 均存在并上传。
- Uniform 类型和 Shader 类型匹配。
- 13 个 Segment 均能解析对应 Resident。
- Back / Front 使用相同 Resident Snapshot。
- 局部宽度使用真实局部宽度，而非固定 18px。
- 流光方向与 UV 映射一致。
- Static T=0 关闭所有动态形变和高光。
- 无 NaN / Infinity。
- 无 GL Error。

## 8.5 Lifecycle Tests

验证：

- Play。
- Pause。
- Resume。
- Reset T=0。
- 0.5x / 1.0x / 1.5x。
- Activity Pause / Resume。
- 60 秒持续播放。
- 无相位跳变。
- 无后台时间累积造成的跳帧。

---

# 9. 阶段 G｜视觉证据与 GPU 验收

## 9.1 必须输出的时间点

T = 0, 1, 2, 4, 8, 12, 20, 40, 60 seconds

每个时间点必须生成：

- Full Scene PNG。
- G01 ROI PNG。
- G02 ROI PNG。
- A01 ROI PNG。
- A02 ROI PNG。
- C01 ROI PNG。
- 五条 Motion State JSON。

## 9.2 必须生成的文件

- FIVE_RESIDENT_DYNAMIC_CONTACT_SHEET.png
- FIVE_RESIDENT_PER_ELEMENT_MOTION_METRICS.json
- FIVE_RESIDENT_SEGMENT_CONTINUITY_REPORT.json
- FIVE_RESIDENT_GPU_RENDER_TELEMETRY.json
- FIVE_RESIDENT_VISUAL_DIFF_REPORT.md
- five_resident_dynamic_evidence.zip
- five_resident_sha256_manifest.txt

Evidence ZIP 必须包含：

- 所有时间点截图。
- 五条独立 ROI。
- Motion Profile。
- Segment Mapping。
- Geometry Lock。
- Motion State JSON。
- GPU Telemetry。
- 自动测试结果。
- SHA256 Manifest。
- 未通过项和原因。

不得只提供一张最终效果图。

## 9.3 视觉验收

必须确认：

1. 五条光丝都能看见内部流动。
2. 五条光丝方向正确。
3. 五条光丝速度不同。
4. 五条光丝节奏不机械同步。
5. 宽度变化可见且自然。
6. 亮度变化可见且自然。
7. 局部波动柔和。
8. Segment 内部没有纹理跳变。
9. Back / Front 遮挡未改变。
10. T=0 静态回归通过。
11. 60 秒循环没有闪断和明显重置。
12. Core、鱼和其他生态元素无回归。

## 9.4 硬件证据等级

必须区分：

- SOFTWARE_MOTION_VERIFIED
- GLES_SHADER_VERIFIED
- HARDWARE_GPU_VISUAL_VERIFIED

没有真实 Android GLES GPU 运行证据时：

HARDWARE_GPU_VISUAL_VERIFIED = NOT_RUN

不得将 Robolectric、CPU 渲染或参数日志冒充真实 GPU 验收。

---

# 10. 性能与回归约束

- 不增加每帧 Mesh 重建。
- 不增加每帧纹理上传。
- 不增加独立 Timer。
- 不在 Render Loop 产生不必要的堆分配。
- 不破坏现有 11 Render Pass。
- 不破坏现有 5 Optical Nodes。
- 不破坏 Core 32 Spatial Z-Slices。
- 不破坏 47 Elements / 28 Dependencies。
- 不改变鱼、水草、岩石、气泡和水体的 Motion Profile。
- 不允许新增严重 GC 或帧率抖动。
- 记录 Resident 单独和全场景的帧耗时、P50/P95/P99、FPS、GC 和内存。

---

# 11. 执行轮次限制

最多三轮定向优化。

每一轮必须提供：

- 修改前基线。
- 修改内容。
- 修改后同条件证据。
- 数值差异。
- 视觉差异。
- 是否真正改善。

如果没有可验证改善，不允许继续堆叠参数。

禁止无限调参、随机改 Shader 或扩大范围。

三轮后仍未通过的项目必须列入 BLOCKED，不得伪造完成。

---

# 12. 最终交付与 GitHub

完成实现后：

1. 所有代码提交到 feat/five-resident-filaments-v1。
2. 不修改 Main。
3. 普通快进推送，不允许 Force Push。
4. 创建独立 PR，目标 Main。
5. Exact-HEAD CI 必须对应最终提交 SHA。
6. 运行 Unit Tests。
7. 运行 API36 相关测试。
8. 编译 AndroidTest。
9. 运行 Lint。
10. 构建 Debug APK。
11. 记录 APK 文件大小和 SHA-256。
12. 输出 Evidence ZIP 下载路径。
13. 输出最终 PR、Commit、CI Run、APK 和 Evidence 链接。

如果当前 AI Studio 环境无法访问 GitHub 写权限：

- 不得声称已经推送。
- 保留本地修改。
- 输出完整 Patch / Commit 状态。
- 明确标记 GITHUB_PUSH_BLOCKED。

如果没有真实设备或可用 API36 Emulator：

- 不得声称硬件 GPU 验收通过。
- 仍完成可运行的基础测试、编译和 APK 构建。
- 将硬件验收标记为 NOT_RUN，并明确剩余步骤。

---

# 13. 最终状态判定

最终报告必须逐项列出：

| Gate | Required Status |
|---|---|
| Contract Read | PASS |
| Source Admission | PASS |
| Geometry Lock | PASS |
| Segment Coordinate Mapping | PASS / UNRESOLVED |
| Profile V1 | PASS |
| Evaluator | PASS |
| Shader Compile | PASS |
| GPU Uniform Upload | PASS |
| Software Motion | PASS |
| T=0 Static Regression | PASS |
| 60s Loop | PASS |
| API36 Tests | PASS / NOT_RUN |
| Hardware GPU Visual | PASS / NOT_RUN |
| Exact-HEAD CI | PASS / FAIL / NOT_RUN |
| Debug APK | BUILT / NOT_BUILT |
| Evidence ZIP | EXPORTED / NOT_EXPORTED |

最终只有在全部必要 Gate 通过后，才允许标记：

FIVE_RESIDENT_FILAMENTS_V1_ACCEPTED

如果硬件 GPU 未运行，即使软件和编译通过，也只能标记：

FIVE_RESIDENT_FILAMENTS_V1_IMPLEMENTED_PENDING_HARDWARE_QA

**禁止将代码完成、单元测试通过或 APK 构建成功，等同于五条 Resident 高保真动态效果验收通过。**

---

# 14. AI Studio 执行纪律

- 严格按阶段顺序执行。
- 不跳过几何锁定。
- 不重新扩展架构。
- 不修改合同外元素。
- 不自行改变 V1 参数。
- 不把未知路径和遮挡补成假数据。
- 不用软件渲染冒充 GPU 结果。
- 不用静态截图冒充动态证据。
- 不以测试数量代替视觉验收。
- 每个 PASS 都必须有对应证据。
- 遇到阻塞必须准确分类，不得提前宣布完成。

**本 Work 唯一交付目标：五条 Resident 在静态几何零破坏的前提下，完成独立、自然、连续、可量化、可验证的动态流动效果。**
