# Five Resident Filaments
# 五条外围光丝动态设计与冻结合同 V1

**Project:** AI Orb Demo / Water World V2.1  
**Contract ID:** WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1  
**Priority:** P0  
**Scope:** G01 / G02 / A01 / A02 / C01  
**Documentation baseline Main HEAD:** 120aeb2c1f716771351bf951826539350bedb512  
**Master SHA-256:** fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589  
**Master Canvas:** 1254 × 1254 px  
**Contract status:** DESIGN_FROZEN_V1; runtime implementation and hardware acceptance remain separate gates.

---

## 0. 合同目标与冻结原则

### 0.1 唯一目标

冻结五条 Resident 外围光丝的静态几何、动态行为、相互节奏、前后景一致性、连续循环和视觉验收标准。

目标效果不是重新画五条光丝，而是在完全保留现有高保真路径、颜色、材质、空间布局和渲染分段的前提下，让每条光丝具有可感知的内部能量流动、局部柔性波动、轻微宽度呼吸和亮度呼吸。

五条光丝必须表现为五条独立的、有空间层次的柔性光丝，不是五条整体平移的线，也不是五条同步摆动的装饰曲线。

### 0.2 冻结范围

本合同仅覆盖：

- 五条 Resident 的静态几何及其 13 个 Render Segments。
- Resident 身份、Segment 映射和规范化路径坐标。
- 每条 Resident 的流动方向、速度、相位和运动节奏。
- 局部横向波动、宽度变化和亮度变化。
- Back / Front Render Pass 的连续性及遮挡约束。
- 统一时间轴、暂停恢复和循环。
- Resident 专项自动化测试、GPU 证据及视觉验收。

禁止修改：

- AI Orb Core 的 32 层空间 Z-Slice、三维流体、旋转、密度、颜色及内部光学。
- 四条鱼及其运动。
- 水草、岩石、气泡、水体、玻璃球壳、焦散、Bloom、Bokeh 和背景。
- 11 个 Render Pass 的整体架构。
- 5 个 Optical Nodes 的整体架构。
- 47 个 Canonical Elements 和 28 条 Canonical Dependencies 的既有身份与关系。

不新增第六条 Resident，不改变五条光丝的身份、数量和原始配色。

### 0.3 关键原则

1. 静态几何冻结，动态行为升级。
2. 五条 Resident 各自独立，但每条 Resident 内部共享一个统一动态状态。
3. 所有运动由既有 WaterWorldMotionTimeline 驱动。
4. 所有 Render Pass 消费同一帧 WaterWorldDynamicFrameSnapshot。
5. T=0 必须回归 Work 2 静态参考状态。
6. 设计参数属于 V1 产品设计目标，不得伪装成从动态视频测量得到的 Ground Truth。
7. 几何身份、路径连接或遮挡关系未被证实的地方必须保留 UNKNOWN，不得猜测补齐。

---

## 1. 唯一输入与静态几何冻结

### 1.1 唯一静态基准

静态基准为 Main HEAD 120aeb2c1f716771351bf951826539350bedb512 对应的 Water World V2.1 Work 2 资产。

Master：

- Canvas：1254 × 1254 px。
- Master SHA-256：fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589。

必须读取并交叉核对：

- releases/water-world-v2-v21/delivery/master_resident_geometry.json
- releases/water-world-v2-v21/delivery/resident_centerlines.json
- releases/water-world-v2-v21/delivery/resident_identity_map.json
- releases/water-world-v2-v21/delivery/resident_occlusion_map.json
- releases/water-world-v2-v21/delivery/resident_width_profiles.json
- releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/03_SCENE_GRAPH/draw_bindings.json
- releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/02_SUPPLEMENTED_ASSETS/geometry/

### 1.2 静态几何锁定内容

每个 Resident 的原始几何必须保持：

- 顶点坐标。
- 顶点法线。
- UV 坐标。
- 索引。
- 顶点顺序。
- Segment 身份。
- Render Pass 归属。
- 原始模型变换。
- 原始颜色、透明度和材质参数。

必须在实施前计算并记录全部 13 个 Mesh 文件的 SHA-256、字节数、顶点数、索引数、UV 范围和边界框；实施后再次计算并逐项比对。

不得通过修改静态 Mesh 文件、重新绘制曲线、整体平移、整体缩放、整体旋转或替换中心线来制造运动。

允许的动态形变必须发生在运行时 Vertex Shader 或等价的非破坏性运行时数据中，不得回写冻结源几何。

### 1.3 五条 Resident 的静态身份

| ID | 冻结颜色 | 视觉身份 |
|---|---|---|
| G01 | #9FE226 | Lime Green 绿色主体 |
| G02 | #FFC247 | Warm Gold 金色主体 |
| A01 | #37E7EF | Cyan 青色主体 |
| A02 | #15D8ED | Cyan Teal 青色主体 |
| C01 | #F6BB48 | Gold 金色细线 |

颜色值来自 resident_identity_map.json 的 v2_color。禁止为了增强动态可见性而重新配色、增加霓虹描边或改变原有材质。

### 1.4 13 个冻结 Render Segments

| Resident | Back Segments：02_RESIDENT_BACK | Front Segments：10_RESIDENT_FRONT |
|---|---|---|
| G01 | G01_back_arch；G01_back_outer_veil | G01_front_lower_sweep |
| G02 | G02_back_upper_arc；G02_back_upper_hairline；G02_back_right_rim | G02_front_right_embrace |
| A01 | A01_back_upper_left | A01_front_left_embrace |
| A02 | A02_back_lower_halo | A02_front_lower_strand |
| C01 | C01_back_lower_thread | C01_front_lower_right |

数量冻结：

- 5 个 Resident 身份。
- 13 个 Render Segments。
- 8 个 Back Segments。
- 5 个 Front Segments。

Segment ID、Mesh 文件和 Draw Binding 不得改名、删除、合并或重新分配 Pass。

### 1.5 Ground Truth 不确定性

当前静态资产存在以下明确限制：

- 五条 Resident 的身份映射仍存在同色交叉歧义。
- G01、G02、A01、A02、C01 的身份状态均为 IDENTITY_UNRESOLVED。
- 五条 Resident 的宽度测量状态均为 UNRESOLVED_IDENTITY_AND_OPTICAL_EDGE_AMBIGUITY。
- 当前 Ground Truth 中 confirmed_occluded_samples 为 0；unclassified 样本不得推断为被遮挡。
- 当前 Back / Front Segment 拆分属于 Render Hypothesis，不是已证实的真实三维遮挡 Ground Truth。

因此：

1. 保留当前 13 个 Segment 和既有 Render Pass。
2. 不把未知采样点当成已确认遮挡。
3. 不补造不可见的隐藏路径。
4. 不将不同 Segment 之间的未知间隙自动连接。
5. 不宣称当前 2D 投影和 Render Pass 已证明真实三维深度关系。
6. V1 只冻结现有可用几何及其渲染分层，不提升 Ground Truth 置信等级。

---

## 2. 五条 Resident 的动态行为

### 2.1 每条 Resident 必须具备四类动态

#### A. 沿路径流动 Flow Motion

这是最主要、最容易被用户感知的动态。

要求：

- 内部能量沿各自冻结路径方向持续运动。
- 形成至少一组清晰可辨的移动高光带。
- 高光带具有柔和前缘、连续主体和渐隐尾部。
- 流光沿路径运动，不得脱离原始路径。
- 不能仅靠整条光丝统一变亮来冒充流动。
- 不允许通过整条光丝整体平移制造流动。
- 周期交接不得出现明显闪断或瞬间跳回。
- 流光强度必须服从原有材质和颜色。

建议的 V1 Shader 表现：

- 一组主流光带。
- 一组低对比度次级细流纹理。
- 主流光带沿规范化弧长坐标运动。
- 主流光带的归一化路径宽度约 4.5%。
- 渐隐尾部长度约为路径长度的 12%。
- 动态高光峰值相对局部基准亮度增加约 12%，不得造成过曝。

上述高光带形态和数值均为 V1 设计目标，不是 Ground Truth 测量值。

#### B. 局部横向波动 Lateral Wave

要求：

- 沿中心线产生连续、非均匀的横向波动。
- 波动方向依据局部横截面方向，而不是固定屏幕 X/Y 方向。
- 不同位置存在空间相位差。
- 波动在 Segment 内部连续、平滑。
- 几何端点和已确认连接点必须避免不自然的突变。
- 不改变原始整体空间布局。
- 不得出现机械弹簧式摆动或大幅蛇形扭曲。

#### C. 宽度呼吸 Width Breathing

要求：

- 以冻结 Mesh 的局部实际宽度为基准做相对缩放。
- 宽度变化沿路径平滑分布。
- 保留细丝、主体、薄纱之间原有宽度层级。
- 不允许使用与真实局部宽度无关的固定像素位移冒充宽度缩放。
- 不得出现粗细突然跳变、局部断裂或宽度翻转。
- 不得回写静态 Mesh。

#### D. 亮度呼吸 Brightness Pulse

要求：

- 亮度变化柔和、连续。
- 每条 Resident 有独立亮度节奏。
- 同一 Resident 的所有 Segment 使用同一个亮度状态。
- 可以叠加沿路径的局部亮度波动，但不得出现整条光丝同步闪烁。
- 不得产生过曝、爆闪或熄灭。

### 2.2 五条 Resident 的 V1 动态参数

以下参数是 V1 产品设计目标，必须明确标记为 FIVE_RESIDENT_FILAMENTS_V1_DESIGN_TARGET。不得标记为 MEASURED_GROUND_TRUTH。

| Resident | Flow Direction | Flow Traversal Period | Phase Offset |
|---|---:|---:|---:|
| G01 | +1 | 16.0s | 0.00 rad |
| G02 | -1 | 18.0s | 1.24 rad |
| A01 | +1 | 20.0s | 2.41 rad |
| A02 | -1 | 14.0s | 3.85 rad |
| C01 | +1 | 22.0s | 5.02 rad |

方向定义：

- +1：沿经过验证的规范弧长坐标递增方向。
- -1：沿经过验证的规范弧长坐标递减方向。

Flow Traversal Period 定义：

一组可识别的主流光特征沿对应规范化可见路径完成一次完整行程所需的时间。

Flow Speed：

speed = direction / traversalPeriodSec

不得直接假设所有 Mesh 的 U 方向一致。必须审计每个 Segment 的 UV 方向并建立显式映射。

### 2.3 宽度呼吸参数

| Resident | Width Amplitude | Width Frequency | 周期 |
|---|---:|---:|---:|
| G01 | ±6.0% | 0.19Hz | 5.26s |
| G02 | ±5.0% | 0.18Hz | 5.56s |
| A01 | ±7.0% | 0.21Hz | 4.76s |
| A02 | ±6.0% | 0.25Hz | 4.00s |
| C01 | ±4.5% | 0.17Hz | 5.88s |

宽度计算以局部实际宽度为基准，不得使用固定 18px 或其他固定屏幕位移替代。

### 2.4 亮度呼吸参数

| Resident | Brightness Amplitude | Frequency | 周期 |
|---|---:|---:|---:|
| G01 | ±9.0% | 0.19Hz | 5.26s |
| G02 | ±8.0% | 0.16Hz | 6.25s |
| A01 | ±10.0% | 0.22Hz | 4.55s |
| A02 | ±9.0% | 0.21Hz | 4.76s |
| C01 | ±7.0% | 0.15Hz | 6.67s |

亮度范围：

brightnessScale = 1 + amplitude × sin(2π × frequency × t + phase)

必须保证亮度变化连续、有限且不产生过曝。

### 2.5 横向波动参数

| Resident | 最大横向波动 |
|---|---:|
| G01 | ±8.0 master px |
| G02 | ±7.0 master px |
| A01 | ±9.0 master px |
| A02 | ±8.0 master px |
| C01 | ±6.5 master px |

统一以 1254 × 1254 Master Canvas 为单位。

时间频率：

waveFrequencyHz = 3 / flowTraversalPeriodSec

波动应沿路径连续分布，不允许每个 Render Segment 独立重置空间波形。

---

## 3. 五条光丝的速度、相位与节奏关系

### 3.1 运动层级

按路径流动速度从快到慢：

1. A02：14s，最快，提供灵动的下方流动节奏。
2. G01：16s，提供稳定的绿色流动。
3. G02：18s，采用反方向金色流动。
4. A01：20s，提供中慢速青色流动。
5. C01：22s，最慢、最细腻的金色辅助流动。

快慢指能量沿各自路径的传播速度，不代表光丝整体在屏幕空间移动。

### 3.2 相位规则

每条 Resident 使用独立 phaseOffsetRad。

相位影响：

- 局部横向波动。
- 宽度呼吸。
- 亮度呼吸。

流动方向和路径坐标映射必须单独计算，不能简单把不同 Segment 的局部 UV 当作相同的全局弧长坐标。

T=0 静态模式必须保持 Work 2 原始画面。不得为了错相而在 T=0 改变光丝几何或材质。

### 3.3 反同步约束

禁止：

- 五条光丝使用相同流动周期。
- 五条光丝使用相同方向。
- 五条光丝使用相同波动频率。
- 五条光丝同时达到最大亮度。
- 五条光丝同时达到最大宽度。
- 五条光丝使用相同局部时间源。
- 同一 Resident 的不同 Segment 独立播放。

必须：

- 使用一个全局时间轴。
- 五条 Resident 使用独立 Profile。
- 不同方向和周期保持冻结。
- 同一 Resident 的所有 Segment 共享同一帧状态。
- 连续 60 秒不出现机械同步的视觉节奏。

---

## 4. 前后景 Segment 连续性与遮挡

### 4.1 一个 Resident，一个动态身份

例如 G01 的三个 Segment：

- G01_back_arch
- G01_back_outer_veil
- G01_front_lower_sweep

必须共享：

- Resident ID = G01。
- 同一 globalTimeSeconds。
- 同一 flowDirection。
- 同一 flowTraversalPeriod。
- 同一 width pulse state。
- 同一 brightness pulse state。
- 同一 wave phase。

G02、A01、A02、C01 同理。

### 4.2 规范化路径坐标

每条 Resident 定义规范化弧长坐标 s ∈ [0,1]。

每个 Segment 的映射表必须记录：

- parentResidentId
- segmentId
- renderPass
- segmentArcLengthStart
- segmentArcLengthEnd
- localUvDirection
- localFlowPhaseOffset
- mappingConfidence
- continuityStatus

映射值必须由真实 Mesh 顶点、UV、中心线和索引关系推导。

如果 Segment 之间没有可验证的连接关系：

- 不得自行插值连接。
- 不得生成隐藏曲线。
- 不得伪造 segmentArcLengthStart / End。
- 不得把未知间隙当成连续路径。
- 允许保留同一 Resident 的统一时间、方向、速度和脉动状态。
- 该跨 Segment 连续性必须标记为 GROUND_TRUTH_UNKNOWN 或 UNRESOLVED，不得判定 PASS。

只有通过几何证据确认映射的 Segment 才能参与跨 Segment 连续性 PASS。

### 4.3 Render Pass 与遮挡

严格保持：

- Back：02_RESIDENT_BACK
- Front：10_RESIDENT_FRONT

不得为增强可见性而交换 Pass。

不得为了流动连续性而取消遮挡层级。

动态验收必须同时检查：

- 每条光丝自身运动。
- Back / Front Pass。
- 与 Core 的视觉关系。
- Segment 交接。
- 是否出现闪断、跳变、重复绘制或穿透。

---

## 5. 时间轴、暂停恢复与连续循环

### 5.1 唯一时间源

使用现有 WaterWorldMotionTimeline。

每帧通过 WaterWorldMotionEvaluator 生成 WaterWorldDynamicFrameSnapshot。

禁止：

- 为每条 Resident 创建独立 Timer。
- 为每个 Segment 创建独立时钟。
- 按帧数累计运动相位。
- 不同 Render Pass 分别计算时间。
- 在 Shader 中引入无法由统一 Snapshot 控制的隐藏时钟。

### 5.2 连续循环

必须保证：

- 流动相位连续。
- 波动相位连续。
- 宽度变化连续。
- 亮度变化连续。
- Segment 不因局部 UV 重置而突然跳变。
- 循环交接处无明显闪断。
- 所有动态数值有限，不出现 NaN / Infinity。

主流光特征的端点淡出和重新进入必须连续，不得直接跳回。

### 5.3 生命周期

| 操作 | 预期行为 |
|---|---|
| 进入 Tab 3 | FINAL_SCENE READY 后自动播放 |
| Pause | 五条 Resident 同时冻结 |
| Resume | 从原相位继续，不跳变 |
| Reset T=0 | 回到 Work 2 静态参考状态 |
| 0.5x | 全部 Resident 按比例减速 |
| 1.0x | 标准播放 |
| 1.5x | 全部 Resident 按比例加速 |
| Activity Pause | 停止推进时间 |
| Activity Resume | 无时间跳变恢复 |

不得破坏既有 Timeline 的帧率无关性、Delta Clamp、Pause/Resume 零跳变和确定性求值能力。

---

## 6. 视觉与量化验收

### 6.1 T=0 静态回归

必须满足：

- 5 个 Resident 身份完整。
- 13 个 Mesh Segment 全部存在。
- 源 Mesh 文件 SHA-256 与实施前完全一致。
- Draw Binding 和 Pass 分配完全一致。
- 颜色、透明度、材质和静态变换保持不变。
- 不新增、不删除、不重绘路径。
- 所有动态形变、动态高光和动态位移在 Static T=0 模式关闭。

全场景目标：

T=0 RGB MAE ≤ 0.6 / 255

同时必须提供 Resident 独立 ROI / Mask 比较。该数值是 V1 回归验收目标，不代表当前所有硬件环境已通过。

### 6.2 动态可见性

每条 Resident 必须能单独观察到：

- 可辨识的流动方向。
- 可辨识的移动高光。
- 与其他 Resident 不同的速度。
- 柔和且非均匀的局部波动。
- 可见但不夸张的宽度呼吸。
- 自然的亮度呼吸。
- 与其他 Resident 不同步的运动节奏。

仅有 Uniform 数值变化而没有屏幕像素变化，不算动态验收通过。

### 6.3 运动量化

以 1254 × 1254 Master Canvas 为测量基准：

| 指标 | V1 验收目标 |
|---|---|
| 2s 流光行程 | 最慢 Resident 至少达到规范路径的约 9% |
| 5s 流光行程 | 最慢 Resident 至少达到规范路径的约 22% |
| 横向波动 | 达到各自冻结幅度，且不越过合理轮廓边界 |
| 宽度变化 | 达到各自冻结相对幅度 |
| 亮度变化 | 达到各自冻结相对幅度 |
| Segment 状态 | 同一 Resident 不存在独立时间源或未经证实的连接 |
| 静态回归 | T=0 不产生动态形变 |

### 6.4 证据采集时间点

必须采集：

T = 0, 1, 2, 4, 8, 12, 20, 40, 60 seconds

每个时间点输出：

- Full Scene PNG。
- G01 / G02 / A01 / A02 / C01 独立 ROI。
- 五条 Resident Motion State JSON。
- Flow Phase / Flow Offset。
- Width Scale。
- Brightness Scale。
- Lateral Wave。
- Segment Mapping / Continuity 状态。

必须生成：

- FIVE_RESIDENT_DYNAMIC_CONTACT_SHEET.png
- FIVE_RESIDENT_PER_ELEMENT_MOTION_METRICS.json
- FIVE_RESIDENT_SEGMENT_CONTINUITY_REPORT.json

### 6.5 GPU 硬件验收

必须在真实 Android GLES 3.0 GPU 上验证：

- 13 个 Draw Segment 实际提交。
- Resident Shader 正确编译和执行。
- 每条 Resident 的 Uniform 正确上传。
- 真实 GPU 输出像素发生预期变化。
- 五条 Resident 的运动方向、速度、亮度和宽度符合参数。
- 60 秒连续播放无明显闪断、跳变和同步问题。

单元测试、Robolectric、CPU 软件渲染、Shader 字符串检查和参数变化均不能代替硬件 GPU 视觉验收。

如果当前环境没有真实设备或硬件 GPU，必须如实标记 HARDWARE_GPU_VISUAL_VERIFIED = NOT_RUN。

---

## 7. 明确禁止项

禁止：

- 五条光丝整体绕 Core 旋转。
- 五条光丝整体平移或缩放。
- 五条光丝完全同步。
- 机械式统一正弦摆动。
- 大幅度蛇形扭曲。
- 高频闪烁或爆闪。
- 整条光丝突然变亮或熄灭。
- 把光丝变成固定粗边框。
- 新增霓虹描边、雷达线、HUD。
- 用大量独立粒子代替光丝内部流动。
- 修改冻结颜色、材质、静态路径和 Mesh。
- 修改 AI Orb Core、鱼、水草、岩石、气泡、水体、玻璃和背景。
- 通过删除旧逻辑、关闭测试或放宽阈值制造 PASS。
- 将未证实的 Segment 连接和遮挡关系写成 Ground Truth。

---

## 8. 冻结资产与交付文件

实施前生成：

1. five_resident_geometry_lock.json
2. five_resident_segment_mapping.json
3. five_resident_motion_profile_v1.json
4. five_resident_motion_acceptance_matrix.md
5. five_resident_source_audit.md

实施后生成：

1. five_resident_motion_runtime_report.md
2. five_resident_shader_audit.md
3. five_resident_segment_continuity_report.md
4. five_resident_dynamic_evidence.zip
5. five_resident_visual_acceptance_report.md
6. five_resident_sha256_manifest.txt

所有资产必须记录：

- 来源 Commit。
- 来源文件路径。
- SHA-256。
- 版本。
- 生成方式。
- 验证状态。
- Ground Truth / Design Target / Runtime Hypothesis 等证据等级。

---

## 9. 证据等级

必须严格区分：

- STATIC_GEOMETRY_LOCKED
- MOTION_DESIGN_V1_FROZEN
- MOTION_RUNTIME_IMPLEMENTED
- SOFTWARE_MOTION_VERIFIED
- GLES_SHADER_VERIFIED
- HARDWARE_GPU_VISUAL_VERIFIED

参数属于设计目标时，必须标记 FIVE_RESIDENT_FILAMENTS_V1_DESIGN_TARGET。

未取得动态参考视频前，不得标记为 MEASURED_GROUND_TRUTH 或 WATER_WORLD_MOTION_VALUES_VALIDATED。

---

## 10. 最终完成定义

只有同时满足以下条件，才允许宣布 V1 实现完成：

1. 五条 Resident 的静态几何完整保留。
2. 13 个 Segment 的身份和 Pass 正确。
3. 五条光丝具有独立、可感知的沿路径流动。
4. 方向、速度、相位和节奏符合 V1。
5. 局部波动、宽度和亮度变化有效。
6. 同一 Resident 的所有 Segment 共享统一动态状态。
7. 不存在未经证实的隐藏路径连接或遮挡推断。
8. 连续播放 60 秒无明显闪断、跳变和机械同步。
9. T=0 静态回归通过。
10. GLES Shader 和真实 GPU 输出得到对应证据。

最终状态：

FIVE_RESIDENT_FILAMENTS_V1_ACCEPTED

**本合同只冻结五条 Resident 光丝。AI Orb Core 和其他水下生态元素不属于本合同。**
