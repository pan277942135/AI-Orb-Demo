# TAB2｜AI Orb 驱动的照片识别视觉与运动总合同

**Contract ID:** TAB2_AI_ORB_PHOTO_RECOGNITION_VISUAL_MOTION_CONTRACT_V1  
**Version:** 1.0  
**Status:** DESIGN_FROZEN  
**Project:** AI Orb Demo / Tab 2 — Photo WaterLight  
**Scope:** P0 Perimeter WaterLight + P1 Internal Recognition Probe + P2 Recognition Reveal & Result Handoff  
**Audience:** 视频生成、视觉设计、Android/GLES 开发、识别 Runtime 集成、QA 与验收  
**Repository:** pan277942135/AI-Orb-Demo  
**Document type:** 产品视觉、运动、数据驱动行为与制作指导合同

---

## 0. 合同目的、效力与边界

本文件将 Tab 2 已冻结的 P0、P1、P2 设计合并为一份统一的产品视觉与运动合同，作为后续：

1. AI 视频生成、关键帧制作、视觉参考片制作；
2. P0 外围水光校准；
3. P1 照片内部识别探测开发；
4. P2 Orb 凝字、鱼种揭晓与 Result Handoff 开发；
5. 三阶段集成、自动化验收和真机视觉验收；

的共同依据。

### 0.1 冻结范围

本合同冻结：

- Tab 2 的视觉叙事和因果关系；
- AI Orb Core 作为唯一 Task Energy 来源；
- 五条 Resident 与 Task Energy 的身份区分；
- P0 的 L1–L4 外围光效结构；
- P0 的 KF01–KF06 六个关键视觉状态；
- P1 的内部光丝、识别节点、目标区域响应；
- P1 与真实识别数据之间的驱动关系；
- P2 的 Orb Return、Orb-to-Glyph、Species Reveal、Residue Settle；
- P2 的结果身份、数据一致性与双端交接；
- 视频生成的连续性、镜头、素材和后期合成要求；
- 后续开发的架构约束、失败处理和验收标准。

### 0.2 本合同不代表实现已经完成

**DESIGN_FROZEN 不等于 IMPLEMENTED、GPU_VERIFIED 或 VISUAL_ACCEPTED。**

本文件是后续视频生成和工程实现的设计输入，不得把合同冻结、静态图、AI 生成视频、CPU 预览或单元测试结果冒充 Android 真机 GPU 验收。

P0 当前已有部分 Task Energy / Perimeter WaterLight 代码基础；P1、P2 的完整行为是否已经实现，必须以实际源码、真实识别回调和运行证据单独核验。本合同不预先宣称任何未验证功能已经完成。

### 0.3 合同层级

本文件是 Tab 2 的产品视觉和运动意图主合同。

- 本合同定义“用户应该看到什么、能量从哪里来、如何运动、何时响应、结果如何交接”。
- 现有 Runtime、坐标、Shader、GPU 资源生命周期和证据代码定义“如何在当前工程中实现”。
- 现有源码是实现现状，不自动成为视觉设计真值。
- 若合同与现有实现冲突，必须记录差异、影响和修复方案；不得静默改变合同或用现有代码反向解释设计。
- 不得另建平行 Orb、Task Energy、Motion Clock、Recognition State Machine 或 Result Pipeline。

---

# 1. 产品目标与统一视觉叙事

Tab 2 的产品主题：

**AI Orb → Photo Recognition**

用户不应只看到照片周围发光，而应直观理解一条完整的因果链：

**AI Orb 发出能量 → 能量抵达照片 → 沿照片外围传播 → 进入照片内部探测 → 对真实目标产生响应 → 能量回到 Orb → Orb 凝聚成鱼种名称 → 结果交接。**

整个体验必须满足：

1. 能量来源始终明确：来自 AI Orb Core。
2. 照片始终是识别主体：照片内容和身份不被动画替换。
3. P0 是外围传播网络，不是静态装饰边框。
4. P1 是由真实识别数据驱动的内部探测，不是假扫描。
5. P2 是 Orb 的连续形态转化和真实结果揭晓，不是普通文字淡入。
6. 同一次识别必须使用同一张照片、同一个 Session/Attempt、同一份识别结果。
7. 三阶段在视觉、时间、数据和坐标上连续，不得拼接成互相无关的特效。

## 1.1 三阶段总结构

| 阶段 | 合同名称 | 核心职责 | 用户感知 |
|---|---|---|---|
| P0 | Perimeter WaterLight | Orb 发射能量并建立照片外围水光网络 | AI 正在处理照片 |
| P1 | Internal Recognition Probe | 能量进入照片、分流、节点探测、目标响应 | AI 正在分析照片中的目标 |
| P2 | Recognition Reveal & Handoff | Orb 回收能量、凝字、揭晓鱼种、交接结果 | AI 已识别出具体鱼种 |

---

# 2. 全局身份、坐标与能量来源合同

## 2.1 唯一能量源

AI Orb Core 是 Task Energy 的唯一逻辑能量源。

能量来源链：

~~~text
AI Orb Core
  → Task Energy
  → Photo Contact
  → Perimeter Network
  → Internal Probe Network
  → Target Response
  → Orb Return
  → Glyph Formation
~~~

不得从照片边框、照片中心、识别节点或屏幕任意位置凭空产生主能量。

## 2.2 Resident 与 Task Energy 必须分离

AI Orb 的五条常驻 Resident：

- G01
- G02
- A01
- A02
- C01

Resident 表示 AI Orb 内核/外围的常驻生命光丝。

Task Energy 表示从 Core 发出、用于当前照片识别任务的独立能量系统。

冻结规则：

- Resident 不得被抽取、替换或改造成 Task Energy。
- Task Energy 不得被描述成五条 Resident。
- Task Energy 的分支、材质层和粒子层可以有多个几何组件，但逻辑来源仍是同一个 Orb Core。
- P0/P1/P2 运行期间，五条 Resident 的身份和既有运动职责保持独立。
- Orb Return 可以使任务能量回流，但不能把 Resident 当作任务能量回收。

## 2.3 逻辑能量网络与 GPU 几何组件的区别

视觉合同定义的是能量的逻辑关系，不限制 GPU 必须使用几条 Ribbon、多少 Mesh、多少 Particle System 或多少 Draw Call。

允许：

- 一条逻辑主能量流由多个 Ribbon/Mesh 组件共同表现；
- 主流分成左右分支；
- L1–L4 以不同几何、材质和粒子组件实现；
- P1 按目标数量和位置生成动态探测分支。

禁止：

- 将实现中的多个 Ribbon 解释为多个互不相关的能量源；
- 因为现有代码使用固定组件数量，就把它冻结为产品视觉上限；
- 以增加固定曲线数量代替有机水光结构。

## 2.4 照片身份和坐标

照片是受保护的原始识别输入。

必须保持：

- 原始照片字节/像素身份；
- Image ID、Photo SHA-256；
- 原始宽高比；
- 当前显示使用的 Photo Transform；
- 圆角、裁切和显示区域的真实几何；
- 模型 ROI 与显示坐标之间的可逆映射。

照片局部归一化坐标约定：

- 左上角：(0, 0)
- 右上角：(1, 0)
- 左下角：(0, 1)
- 右下角：(1, 1)
- 底边中心：(0.5, 1)

此约定用于合同描述。工程实现必须经过当前 Photo Transform 转换到 Surface/GL 坐标，不得混用 Window、Compose、Photo Local、Texture UV 和 NDC 坐标。

---

# 3. P0｜照片外围水光合同

**Contract:** TAB2_PERIMETER_WATERLIGHT_CONTRACT_V1  
**Status:** DESIGN_FROZEN

## 3.1 P0 目标

P0 让用户第一眼理解：

- 能量从 AI Orb 发出；
- 能量抵达照片；
- 能量沿照片外围传播；
- 外围光效具有液态、有机、连续的识别能量感。

P0 不是普通照片描边，也不是四条固定曲线。

## 3.2 照片与 Orb 的布局关系

- 照片位于上方，是识别主体。
- AI Orb 位于照片下方，是任务能量源。
- Orb 上方发射锚点与照片底边中心建立连续能量通道。
- 照片外围水光沿照片真实圆角边界分布。
- 外围水光可局部外扩、弯曲、波动和产生分支，但不能变成机械矩形框。
- 不得改变照片内容、位置、尺寸和比例。

## 3.3 L1–L4 四层光效

### L1｜Primary Energy Flow — 冰蓝主水光带

职责：定义主要能量路径和传播方向。

视觉要求：

- 冰蓝、青蓝、冷白高亮；
- 连续、有体积感的液态水光；
- 宽度、亮度、透明度和边缘厚度动态变化；
- 主能量脉冲清晰可见；
- 底边中心接触后向左右分流；
- 沿底边、圆角、左右侧边向顶部传播；
- 顶部汇合后进入持续循环。

禁止：

- 固定矩形描边；
- 均匀宽度的 Neon Stroke；
- 机械直线运动；
- 仅靠 Canvas Stroke 或模糊光晕假装液态材质。

### L2｜Organic Water Veil — 翡翠色水雾薄纱

职责：提供外围水体厚度、透明度和有机流动感。

视觉要求：

- 翡翠绿、透明青绿、蓝绿色；
- 形成宽于 L1 的柔性水体区域；
- 随主能量产生波动、扩散、折叠、分离和融合；
- 具有独立相位和轻微滞后；
- 与 L1 有关联但不能完全重合。

禁止：

- 固定透明渐变矩形；
- 简单 Gaussian Blur 代替水体；
- 遮盖照片主体。

### L3｜Golden Energy Filaments — 香槟金能量细丝

职责：表现能量网络的精细结构、分支和局部高能节点。

视觉要求：

- 香槟金、浅金、暖白；
- 从 L1/L2 的局部能量区域自然分化；
- 不规则、连续、可分支和交汇；
- 允许缠绕、分离、融合和消散；
- 在分流、转角和汇合位置产生短暂局部高亮；
- 数量由视觉需要决定，不设固定条数上限。

禁止：

- 固定五条或其他固定数量曲线；
- 均匀排列的金色边框；
- 所有细丝同步、同速、同相位。

### L4｜Droplets & Sparkles — 水滴与聚光粒子

职责：补充微观水体细节和局部光学响应。

视觉要求：

- 不同尺寸、透明度和折射表现的水滴；
- 粒子沿实际能量方向运动；
- 转角、分流和汇合处允许局部聚光；
- 粒子数量、密度和生命周期动态变化；
- 稀疏、有方向、有层次。

禁止：

- 无方向随机爆炸；
- 均匀粒子点阵；
- 传统星星闪烁；
- 以粒子数量替代真实水光结构。

### 3.3.1 四层整体关系

- L1 决定能量主路径；
- L2 决定外围水体体积；
- L3 决定精细能量网络；
- L4 决定微观光学细节。

四层共同构成一个有机水光网络，不能作为四条互不相关的装饰曲线。

## 3.4 P0 传播路径

~~~text
Orb Core
  → Orb Top Emission Anchor
  → Photo Bottom Center Contact
  → Bottom Split
  → Left / Right Bottom Corners
  → Left / Right Side Ascend
  → Top Converge
  → Perimeter Loop
~~~

左右两支属于同一逻辑能量流，允许速度、形态和相位存在自然差异，不允许机械镜像复制。

## 3.5 KF01–KF06 六个关键视觉状态

| Keyframe | 名称 | 必须出现的视觉行为 |
|---|---|---|
| KF01 | ORB_RELEASE | Orb Core 能量增强并向照片发射主能量流 |
| KF02 | ARRIVAL | 能量抵达照片底边中心，形成接触扩散 |
| KF03 | BOTTOM_SPLIT | 能量沿底边向左右分流 |
| KF04 | SIDE_ASCEND | 左右能量沿两侧向上攀升 |
| KF05 | TOP_CONVERGE | 两路能量在顶部汇合，产生局部能量聚光 |
| KF06 | LOOP | 四边外围持续循环，Orb 持续补充任务能量 |

运行时已有别名可能使用 BOTTOM_CONTACT 对应 ARRIVAL、TOP_JOIN 对应 TOP_CONVERGE。别名只属于实现映射，不得改变冻结的视觉语义。

## 3.6 P0 连续运动规则

- 六帧是关键视觉状态，不是六张静态图。
- 状态之间必须连续插值。
- L1 为运动主导层。
- L2 相对 L1 具有独立相位和轻微滞后。
- L3/L4 根据局部能量动态响应。
- 左右分支不使用完全相同的轨迹。
- KF06 必须无明显断点地持续循环。
- 暂停/恢复不得产生位置、相位或能量跳变。
- 不允许在 Loop 与下一轮能量脉冲之间整体重置。

本合同不为 KF01–KF06 重新规定未经确认的单帧毫秒数。具体时间必须映射到统一运动时钟，并通过关键帧证据验证。

---

# 4. P1｜照片内部识别探测合同

**Contract:** TAB2_INTERNAL_RECOGNITION_PROBE_CONTRACT_V1  
**Status:** DESIGN_FROZEN

## 4.1 P1 目标

P1 让用户理解 AI 不只是在照片外围发光，而是在照片内部探测真实目标。

P1 的三类核心视觉元素：

1. Internal Probe Filaments — 内部探测光丝；
2. Recognition Nodes — 识别节点；
3. Target Response Field — 目标区域响应。

P1 必须由真实识别输入、模型回调和目标坐标驱动，不得只根据动画时间或随机位置显示“已找到目标”。

## 4.2 内部探测光丝

三类光丝：

| 类型 | 职责 | 视觉形态 |
|---|---|---|
| Primary Probe | 主探测路径 | 冰蓝、连续、清晰 |
| Secondary Probe | 次级探测分支 | 半透明、细窄、柔性 |
| Micro Filament | 微观探测细丝 | 极细、短暂、局部 |

运动要求：

- 从 P0 外围能量网络的有效入口进入照片；
- 主光丝向内部延伸；
- 接近目标区域时动态分支；
- 次级光丝从主路径分化并探索局部区域；
- 微观光丝在目标附近短暂出现；
- 光丝允许分离、汇合、消散和重新建立；
- 分支数量由真实目标和视觉需要决定，不冻结固定数量；
- 所有内部光丝必须可追溯到 Orb Core 的 Task Energy。

禁止：

- 所有光丝从照片中心凭空出现；
- 固定放射状射线；
- 固定数量的机械曲线；
- 全图扫描网格；
- 直接描绘鱼体轮廓；
- 用 BBOX/XBOX 伪装成能量探测。

## 4.3 Recognition Nodes — 识别节点

节点类型：

| 类型 | ID | 职责 |
|---|---|---|
| Branch Node | N1 | 外围能量进入照片后形成分流 |
| Analysis Node | N2 | 沿内部探测路径传递分析脉冲 |
| Target Node | N3 | 在真实目标附近产生响应 |

节点不是固定数量或固定拓扑。必须根据实际探测路径、目标数量和目标位置动态建立。

节点状态：

| 状态 | 视觉行为 |
|---|---|
| DORMANT | 微弱、低透明度 |
| ACTIVATING | 冰蓝亮度增强 |
| PROCESSING | 连续脉冲传递 |
| RESPONDING | 局部扩散和能量聚集 |
| CONFIRMED | 真实确认后出现香槟金局部高亮 |
| REJECTED | 能量衰减并消散 |

CONFIRMED 必须由真实识别状态触发，不得由固定延时或动画完成百分比伪造。

## 4.4 Target Response Field — 目标区域响应

三层表现：

1. Target Halo：目标区域附近的柔性局部光晕；
2. Energy Concentration：探测光丝向目标区域聚集；
3. Recognition Pulse：目标节点产生局部能量响应。

目标响应必须：

- 根据真实模型 ROI 生成；
- 使用不规则、柔性边界；
- 保留原始照片细节；
- 不遮挡鱼体关键区域；
- 不形成矩形框；
- 不描绘精确鱼体轮廓；
- 不使用固定位置和固定尺寸。

如果识别模型没有提供有效 ROI，则不得声称知道目标的精确位置。此时可以保留外围能量和非定位性内部探索，但不生成虚假的 Target Halo。

## 4.5 真实识别数据接口

最少需要以下语义字段；实际字段名可适配现有 Runtime：

| 字段 | 用途 |
|---|---|
| session_id | 识别会话身份 |
| attempt_id | 当前识别尝试 |
| image_id | 当前图片身份 |
| photo_sha256 | 照片字节身份 |
| source_frame_id | 来源帧身份 |
| target_id | 多目标区分 |
| roi_normalized | 目标 ROI 的归一化坐标 |
| confidence | 模型实际置信度 |
| recognition_status | 当前识别状态 |
| event_id / sequence | 事件顺序、去重和乱序保护 |

坐标转换必须经过当前 Photo Transform。不能直接把模型输入 Resize/Letterbox 坐标当成屏幕坐标。

## 4.6 P1 状态序列

~~~text
PROBE_ARM
  → BRANCH_FORM
  → INWARD_TRAVEL
  → NODE_ENGAGE
  → TARGET_RESPONSE
  → ENERGY_RECYCLE
~~~

P1 可以在 P0 进入 KF06 / PERIMETER_LOOP 后启动。P1 不需要重新播放 P0 的 Orb Release。

## 4.7 P1 场景处理

- 单目标：建立与该目标 ROI 对应的探测分支和局部响应。
- 多目标：每个目标拥有独立 target_id、探测分支和响应状态。
- 无目标：不生成虚假目标节点或目标光晕。
- 低置信度：保持 PROCESSING/候选状态，不提前 CONFIRMED。
- 识别失败：内部能量合理衰减，交给失败结果路线。
- 旧照片迟到回调：必须拒绝，不得驱动当前照片。
- 多次乱序回调：按 Session/Attempt/Event Sequence 校验。
- ROI 缺失或无效：不做精确定位响应。
- 照片变换改变：必须重新校验 ROI 到屏幕的映射。

---

# 5. P2｜识别完成、Orb 凝字与结果交接合同

**Contract:** TAB2_P2_RECOGNITION_REVEAL_CONTRACT_V1  
**Status:** DESIGN_FROZEN

## 5.1 P2 目标

P2 是整个识别视觉流程的高潮：

**AI Orb 回收能量 → Orb 几何解体和形变 → 真实鱼种名称由水体和能量路径凝聚 → 名称稳定 → 原始照片和识别结果交接到 Result。**

Orb 不得直接消失后显示普通文字。凝字必须是 Orb 的连续几何和材质转化。

## 5.2 Stage 10｜ORB_RETURN — 能量回收

**标称时长：350ms**

- P1 内部探测光丝逐渐收束；
- 照片外围 L1–L4 保持连续运动；
- 目标响应节点完成最后一次真实反馈；
- 分散的任务能量沿连续路径回到 Orb；
- Orb Core 亮度增强；
- 球体核心重新形成稳定结构；
- Result Snapshot 可开始准备，但不提前展示鱼种详情。

禁止：

- Orb 突然跳回；
- 光丝瞬间消失；
- Result 提前出现；
- 使用旧照片或旧 Attempt 的识别结果。

## 5.3 Stage 11｜ORB_TO_NAME — Orb 向文字形态转化

**标称时长：850ms**

三个连续动作：

### A. Core Expansion

- 水晶内核向外舒展；
- 内部水体流动和折射增强；
- 玻璃边缘逐渐失去完整球形；
- 核心能量向外扩展。

### B. Liquid Decomposition

- 玻璃、水体、能量光丝逐渐分离；
- 原有球体材质和能量来源保持可追溯；
- 形成不同粗细、透明度和深度的能量带；
- 局部能量开始聚集。

### C. Glyph Preparation

- 无序能量逐渐进入真实文字几何结构；
- 出现有方向性的笔画雏形；
- 金色细丝与冰蓝水体融合；
- 为真实 Glyph Path 的生成和绘制做准备。

禁止：

- 隐藏 Orb 后直接显示文字；
- 普通 Text Fade；
- 预制鱼种图片替代；
- 与 Orb 无关的新字体动画。

## 5.4 Stage 12｜SPECIES_NAME_REVEAL — 鱼种名称凝字

**标称时长：800ms**

鱼种名称必须来自当前识别 Attempt 的真实 Classifier Top1 或经确认的结果。

Glyph 生成和绘制要求：

1. 根据真实中文鱼种名称生成 Glyph Path；
2. 采用支持中文的字体 shaping/轮廓路径生成流程；
3. 将文字轮廓转换为可绘制几何；
4. Orb 水体和能量光丝沿真实 Glyph Path 运动；
5. 能量逐步覆盖真实笔画；
6. 玻璃、水体、金色光丝共同形成文字材质；
7. 所有有效笔画实际绘制完成后，才允许名称稳定。

禁止：

- 固定中文名称；
- 把识别结果写死为示例鱼种；
- 仅改变 TextView/Compose Text 的 Alpha；
- 用视频生成模型生成最终可读中文鱼种文字；
- 用与识别结果不一致的预制字形。

### 5.4.1 Glyph 状态

| 状态 | 语义 |
|---|---|
| SPECIES_RESULT_READY | 真实分类结果已准备 |
| GLYPH_PATH_READY | 真实鱼种名称 Glyph Path 已生成 |
| GLYPH_REVEAL_RENDERED | Renderer 已实际绘制完整 Glyph Path |
| NAME_STABLE | 名称身份、路径和绘制回执均已验证 |

NAME_STABLE 必须同时满足：

- Classifier Top1 有效；
- Species ID 与当前 Session/Attempt 一致；
- Glyph Path 完整且边界有效；
- 实际绘制进度达到 100%；
- 绘制回执属于当前照片和当前识别结果。

## 5.5 Stage 13｜RESIDUE_SETTLE — 余辉稳定与结果接管

**标称时长：450ms**

Stage 13 内部五个子阶段，严格按顺序执行，不增加新的顶层 Stage：

| 子阶段 | 名称 | 职责 |
|---|---|---|
| 13.1 | NAME_STABLE | 鱼种名称身份、路径和绘制稳定 |
| 13.2 | PHOTO_SETTLE | 同一张原始照片恢复稳定布局 |
| 13.3 | RESULT_SCENE_HANDOFF | Producer 提交 Snapshot，Consumer 校验并 ACK |
| 13.4 | RESULT_INFO_ARRIVAL | 同一 Snapshot 的照片、鱼种和识别信息进入 Result |
| 13.5 | RESULT_SCENE_STABLE | Result 页面正式接管，Orb 余辉自然消散 |

交接必须是：

~~~text
Result Producer
  → Immutable Result Snapshot
  → Consumer Validation
  → Consumer ACK
  → Result Scene Stable
~~~

仅仅调用回调或发送事件，不等于交接成功。没有 Consumer ACK，不得报告 RESULT_SCENE_STABLE。

## 5.6 P2 时间合同

| 阶段 | 标称时间 |
|---|---:|
| Stage 10 — ORB_RETURN | 350ms |
| Stage 11 — ORB_TO_NAME | 850ms |
| Stage 12 — SPECIES_NAME_REVEAL | 800ms |
| Stage 13 — RESIDUE_SETTLE | 450ms |
| **P2 合计** | **2,450ms** |

全流程继续使用既有冻结约束：

- 总体视觉流程：13 个顶层 Stage，标称 6,000ms；
- 统一时钟起点：照片稳定的第一帧；
- 模型等待上限：60,000ms；
- 不新增独立 SceneClock；
- 不新增局部 Timer；
- 模型推理与视觉流程并行；
- P2 的 2,450ms 是模型结果准备好且各硬门控满足时的标称视觉时间；
- 模型未准备好时不得虚构鱼种、提前确认或强制越过门控；
- 60 秒等待超时必须进入明确失败/超时路线。

P0 KF01–KF06、P1 内部探测事件和 P2 Stage 10–13 必须映射到同一全局时间与事件系统。实现阶段必须输出明确的 Event-to-Stage Mapping，不能创建第二套时钟，也不能擅自改变 6,000ms 和 60,000ms 冻结约束。

## 5.7 P2 四条结果路线

| 路线 | 适用条件 | 视觉行为 |
|---|---|---|
| FULL_REVEAL | 真实鱼种身份有效，且可信闭合 Mask/P* 几何可用 | 完整目标视觉揭晓，再进入 Orb Return 和凝字 |
| ORB_ONLY | 鱼种分类结果有效，但缺少可信轮廓或 P* | 不伪造鱼体轮廓，允许 Orb Return 和真实鱼种凝字 |
| STATIC_RESULT | 低置信度、候选结果或需要人工确认 | 不做确定性凝字，进入候选/人工确认 Result |
| FAILURE | 当前 Attempt 识别失败、超时或输入无效 | 不显示虚假鱼种，进入明确失败状态 |

重要规则：

- Mask 缺失不等于分类结果必然失败。
- 没有可信 ROI 时，不显示精确目标位置。
- 没有有效 Species ID 时，不显示确定性鱼种名称。
- 不允许把上一张照片的结果用于当前照片。

---

# 6. Result Snapshot 与身份一致性合同

P2 的 Orb 凝字、照片稳定和 Result 页面必须消费同一个不可变结果快照。

最低身份字段：

| 字段 | 用途 |
|---|---|
| session_id | 识别会话身份 |
| attempt_id | 当前识别尝试 |
| image_id | 当前照片身份 |
| photo_sha256 | 原始照片字节身份 |
| source_frame_id | 来源帧身份 |
| target_id | 多目标身份（适用时） |
| species_id | 鱼种身份 |
| species_display_name | 当前真实结果显示名称 |
| classifier_version | 识别模型版本 |
| confidence | 实际置信度 |
| roi_normalized | 目标 ROI（可缺省，但不得伪造） |
| glyph_path_id | 凝字几何身份 |
| geometry_version | 几何版本 |
| photo_transform | 照片坐标与显示坐标关系 |
| event_id / sequence | 事件顺序、去重和乱序保护 |

要求：

- Snapshot 创建后不可变；
- Glyph 和 Result 使用同一 Species ID/Display Name；
- 照片使用同一 Image ID/Photo SHA；
- Consumer 校验 Session/Attempt/Image 身份；
- 旧回调、乱序回调、重复事件必须拒绝或去重；
- Result 不得重新识别或独立生成另一个鱼种名称；
- Result 不得重新计算一套与识别过程冲突的照片几何；
- Consumer ACK 必须包含当前 Snapshot 身份；
- 任一身份不匹配，交接失败并保留可诊断错误。

---

# 7. P0/P1/P2 色彩、材质与连续性合同

## 7.1 色彩语义

| 元素 | 色彩 |
|---|---|
| L1 主水光/主探测光丝 | 冰蓝、青蓝、冷白高亮 |
| L2 水雾/水体 | 翡翠绿、透明青绿、蓝绿色 |
| L3 能量细丝 | 香槟金、浅金、暖白 |
| L4 水滴/粒子 | 透明水滴、冰蓝微光、少量金色高光 |
| Branch Node | 冰蓝 |
| Analysis Node | 青绿 |
| Confirmed Target Node | 香槟金 |
| Target Halo | 冰蓝至淡金柔和渐变 |
| Glyph | 冰蓝水体、透明玻璃、香槟金局部笔画高亮 |

金色只表达局部能量集中、真实目标响应或确认，禁止全屏金色泛滥。

## 7.2 材质连续性

从 P0 到 P2 必须保持同一套水体/玻璃/光丝材质语义：

- 水体有透明度和内部厚度；
- 玻璃有折射、Fresnel 和空间边缘；
- 光丝具有宽度、横截面和内部高光；
- 粒子有方向、生命周期和局部光学响应；
- Glyph 是 Orb 能量转化后的几何结果，不是突然切换到普通字体。

## 7.3 禁止视觉元素

除非未来另行批准，本合同禁止：

- 静态矩形霓虹边框；
- 手机外框；
- 固定数量的机械曲线；
- 无方向随机粒子爆炸；
- HUD、雷达线、十字准星；
- 全图扫描网格；
- BBOX/XBOX；
- 虚假的鱼体轮廓；
- 未经真实结果确认的金色目标标记；
- 固定中文鱼种名称；
- 普通文字淡入冒充 Orb 凝字；
- 无来源的能量流；
- 照片内容被 AI 视频生成模型擅自重绘。

---

# 8. 视频生成制作合同

本节用于后续生成视觉参考视频、分镜、关键帧和开发对照素材。

## 8.1 视频制作目标

视频必须表现同一个 Tab 2 场景从 P0 到 P2 的连续变化，而不是把三套不相关的视觉片段拼在一起。

视频是设计参考和视觉验收输入，不是生产 GPU 实现证据。

## 8.2 统一场景锁定

所有镜头必须锁定：

- 同一张源照片；
- 同一个 AI Orb 形态、材质和位置；
- 同一套背景；
- 同一张照片的尺寸、圆角、裁切和位置；
- 同一组五条 Resident 身份；
- 同一套 L1–L4 色彩语义；
- 同一视角、镜头和构图；
- 同一套能量来源和运动方向。

默认使用 Tab 2 实际 App 布局作为构图基准。若制作全屏手机演示，使用竖屏画布；具体分辨率和比例必须匹配冻结的 App 参考，不得为适配视频而裁掉 Orb、照片或外围水光。

默认相机：

- 固定正面/轻微设计视角；
- 不做无依据的推拉摇移；
- 不旋转整个 App 场景来假装 Orb 内核运动；
- 保持照片和 Orb 的相对位置；
- 不改变场景透视。

## 8.3 视频分段制作

建议分段生成，再进行连续性合成：

### Clip A — P0 Perimeter WaterLight

必须包含 KF01–KF06：

1. Orb Release；
2. Photo Arrival；
3. Bottom Split；
4. Side Ascend；
5. Top Converge；
6. Continuous Loop。

### Clip B — P1 Internal Recognition Probe

必须包含：

1. 外围网络保持运行；
2. 探测光丝进入照片；
3. 主/次级分支生成；
4. 分析节点逐次响应；
5. 真实目标区域产生局部响应；
6. 探测能量回收。

### Clip C — P2 Orb Return & Orb-to-Glyph

必须包含：

1. 内部探测光丝收束；
2. 能量返回 Orb；
3. Orb Core Expansion；
4. Liquid Decomposition；
5. Glyph Preparation。

### Clip D — P2 Species Reveal & Result Handoff

必须包含：

1. 真实 Glyph Path 逐笔凝聚；
2. 名称完整稳定；
3. 原始照片稳定；
4. Result Snapshot 交接；
5. Result 页面接管；
6. Orb 余辉消散。

## 8.4 视频生成模型的职责边界

视频生成模型适合生成：

- 液态水光运动参考；
- 透明玻璃和水体形变参考；
- 有机光丝的分支和流动参考；
- 节点脉冲和粒子运动参考；
- Orb 解体和重新聚合的视觉参考；
- 光学氛围、材质和局部运动参考。

视频生成模型不得作为以下内容的事实来源：

- 最终鱼种名称；
- 精确可读中文 Glyph；
- 鱼种分类结果；
- 目标 ROI；
- 鱼体 Mask；
- 几何真值；
- Result Snapshot；
- 实际 Android GPU Draw/Pass 证据。

最终中文鱼种名称必须由真实 Classifier 输出并通过 Glyph Path 后期/运行时绘制。禁止依赖视频生成模型直接生成可读中文鱼种名称。

## 8.5 视频主提示词模板

所有分段提示词必须继承以下共同描述：

~~~text
A single coherent AI-powered photo recognition scene.
A transparent liquid crystal AI Orb below one unchanged source photograph.
The AI Orb core is the only source of task energy.
Organic translucent icy-blue waterlight, emerald water veil,
fine champagne-gold filaments and sparse refractive water droplets.
The energy visibly travels from the Orb to the photo,
around the real rounded photo perimeter, then branches into the photo
as delicate localized probing filaments.
The same energy returns to the Orb and transforms into real glyph-shaped
liquid light before a clean result handoff.
Preserve the exact source photograph, crop, geometry, Orb identity,
camera, layout, color semantics and spatial continuity.
Cinematic optical realism, translucent volume, fluid motion,
controlled highlights, organic branching, no mechanical neon frame.
~~~

## 8.6 P0 分段提示词补充

~~~text
Show one continuous energy stream emitted from the AI Orb core.
It reaches the bottom-center contact point of the unchanged photo,
splits naturally toward left and right, follows the rounded lower corners,
ascends along both sides, converges at the top and becomes a continuous loop.
The icy-blue primary liquid flow leads the motion.
The emerald translucent veil follows with a soft phase delay.
Champagne-gold filaments branch irregularly from local energy regions.
Sparse droplets and sparkles travel along the real flow direction.
No static rectangular border, no uniform neon stroke, no random explosion.
~~~

## 8.7 P1 分段提示词补充

~~~text
Keep the perimeter waterlight active.
Allow delicate energy filaments to branch inward from the perimeter
and travel through the unchanged photograph.
Create irregular primary and secondary probing paths.
Small recognition nodes activate sequentially along the paths.
Only the real target region supplied by the source recognition data
may produce a localized soft halo and concentrated response.
Do not draw bounding boxes, crosshairs, fish outlines or a full-image grid.
If no target coordinates are supplied, do not invent a target highlight.
~~~

## 8.8 P2 分段提示词补充

~~~text
The internal probing filaments gradually return to the same AI Orb.
The Orb does not disappear or cut away.
Its transparent glass shell, liquid core and energy filaments expand,
separate and transform continuously into flowing glyph-shaped paths.
The paths must follow the exact vector glyph geometry supplied in post-production.
Use no generated pseudo-Chinese text.
After the exact species glyph is fully drawn and stable,
the original photograph and the same recognition result transition
smoothly into the result layout.
~~~

## 8.9 视频负面提示词

~~~text
No static rectangular neon border.
No phone mockup unless explicitly requested.
No HUD, radar, crosshair, bounding box or scanning grid.
No fish silhouette outline.
No invented target location.
No random energy source.
No extra Orb.
No duplicated photo.
No photo subject mutation.
No camera jump, crop jump or perspective drift.
No mechanical mirrored curves.
No fixed-count decorative ribbons.
No particle explosion.
No illegible pseudo-Chinese text.
No hard cut replacing Orb with ordinary text.
No false success badge or fabricated recognition result.
~~~

## 8.10 视频输出与验收素材

每轮视频制作应交付：

- 原始参考输入清单及 SHA-256；
- P0 KF01–KF06 关键帧；
- P1 六个探测状态关键帧；
- P2 Stage 10–13 关键帧；
- 分段视频；
- 完整连续合成视频；
- 关键帧时间索引；
- 使用的提示词和负面提示词；
- 后期 Glyph Path/真实文字叠加说明；
- 与源照片的内容一致性检查；
- 视频与冻结合同的差异报告。

生成视频只能作为视觉参考，不得代替真实 Runtime 事件或 GPU 验收。

---

# 9. 后续 Android/GLES 开发指导合同

## 9.1 必须复用现有工程

优先审计和复用当前 Tab 2 相关模块：

- AIOrbMotionController；
- TaskEnergyController；
- TaskEnergyGeometry；
- PerimeterPathGeometry；
- PerimeterWaterLightRenderer；
- TaskEnergyRibbonRenderer；
- TaskWaterLightShaders；
- TaskEnergyEvidence；
- TaskSceneLayoutSnapshot；
- PhotoFrameGeometry；
- OrbViewportTransform。

不得未经审计重建同等职责的新系统。

## 9.2 单一运动时钟

- 使用现有 AIOrbMotionController 的单调运动时钟作为 Orb/Task 运动的共同时间基础；
- Task Energy、P0、P1、P2 共享统一时间/事件协调机制；
- 禁止为 P1/P2 创建独立 Timer 或 SceneClock；
- 动画状态由真实 Runtime Event 和统一 Motion Snapshot 驱动；
- Shader 负责渲染，不负责自行决定识别状态和阶段完成；
- 暂停/恢复必须保持相位连续；
- 低帧率和帧间隔波动不能导致关键事件丢失或阶段跳跃。

## 9.3 P0 开发要求

- 审计现有 KF01–KF06；
- 审计现有四个 Task Energy GPU Ribbon 的逻辑身份；
- 审计 L1/L2/L3 和 L4 粒子是否真正分层；
- 验证 Orb Anchor Top 到 Photo Bottom Center 的真实连接；
- 验证圆角路径和左右分支；
- 验证 KF06 连续 Loop；
- 保留五条 Resident 的独立身份；
- 不因本合同而机械地把现有四个 GPU Ribbon 改成四个逻辑能量源。

现有四个 Task Energy Ribbon 可以作为实现组件，但必须服从一个逻辑 Orb 能量源的产品语义。

## 9.4 P1 开发要求

- 从 P0 的外围网络建立探测入口；
- 根据真实 ROI/目标身份生成内部路径；
- N1/N2/N3 节点由实际探测拓扑驱动；
- 多目标使用独立 target_id；
- 无 ROI 时不生成精确定位光晕；
- 真实识别状态驱动节点状态；
- 旧 Attempt、旧照片和乱序事件必须隔离；
- 目标响应不得反向修改原始照片；
- P1 不得建立第二个识别模型或平行识别状态机。

## 9.5 P2 开发要求

- 复用真实识别结果；
- 根据真实 species_display_name 生成 Glyph Path；
- 采用可验证的矢量路径，而不是普通文字淡入；
- Orb 形变必须有连续几何/材质过渡；
- NAME_STABLE 依赖真实 Glyph Path 和绘制回执；
- Result 使用同一不可变 Snapshot；
- Producer/Consumer 必须双端确认；
- 结果失败时走明确失败路线，不得填充固定鱼种。

## 9.6 渲染与性能

- 所有动态元素应使用设备无关、照片局部归一化或真实物理像素转换参数；
- 避免依赖单一设备的固定像素位置；
- 对 GPU 资源实行明确创建、复用和释放；
- 不得每帧重复解码/上传相同纹理；
- 所有 FBO、Shader、Texture 和 Buffer 生命周期可追踪；
- 诊断模式不得改变正式运动状态；
- 证据采集必须与被采集帧的状态一致；
- 真机 GPU 结果与 CPU 参考必须明确区分。

---

# 10. 自动化验收与证据合同

## 10.1 P0 验收

- 能量从 Orb Core 发出；
- 主流到达照片底边中心；
- 左右分流；
- 左右侧边攀升；
- 顶部汇合；
- KF06 连续循环；
- L1–L4 有独立视觉贡献；
- 五条 Resident 独立存在；
- 照片原始内容未变化；
- 关键状态无跳帧和重置。

## 10.2 P1 验收

至少覆盖：

- 单目标；
- 多目标；
- 无目标；
- 低置信度；
- 高置信度确认；
- ROI 缺失；
- ROI 坐标转换；
- 照片切换；
- 旧回调；
- 乱序/重复事件；
- 识别失败。

验证每种情况是否生成正确的光丝、节点和 Target Response，不允许假阳性视觉响应。

## 10.3 P2 验收

- Orb Return 能量连续；
- Orb 真实形变；
- Glyph Path 来自真实鱼种名称；
- Glyph 完整绘制；
- NAME_STABLE 真实回执；
- 原始照片稳定；
- Snapshot 身份一致；
- Consumer ACK；
- Result Scene Stable；
- FULL_REVEAL / ORB_ONLY / STATIC_RESULT / FAILURE 路线正确。

## 10.4 证据内容

每次实现交付至少包含：

- Git Commit SHA；
- 源码变更清单；
- 单元/集成测试报告；
- KF01–KF06 关键帧；
- P1 探测状态关键帧；
- P2 凝字与交接关键帧；
- 最终连续视频；
- 原始照片 SHA-256；
- ROI/Target/Session/Attempt 事件日志；
- Glyph Path 和绘制回执；
- Result Snapshot 身份及 Consumer ACK；
- GPU Vendor/Renderer/GLES 信息；
- 实际 Draw/Pass/Frame Time；
- PixelCopy 与 FBO 证据（适用时）；
- Evidence ZIP 和 SHA-256。

Robolectric、CPU Renderer、软件 GLES 和 AI 生成视频均不得冒充 Android 真机硬件 GPU 验收。

---

# 11. 后续实施顺序与变更控制

建议后续按以下顺序执行：

1. **SOURCE_LOCK**：锁定当前 Tab2 源码、照片参考和合同版本。
2. **P0_AUDIT_AND_ALIGNMENT**：审计现有 P0，补齐与冻结视觉合同的差异。
3. **P0_VISUAL_VERIFICATION**：完成 KF01–KF06 与四层外围水光验收。
4. **P1_DATA_AND_GEOMETRY**：接入真实 ROI、目标身份、探测路径和节点状态。
5. **P1_RUNTIME_AND_GPU**：实现内部探测 GPU 渲染和多目标/异常处理。
6. **P1_VISUAL_VERIFICATION**：完成真实识别数据驱动的内部探测验收。
7. **P2_GLYPH_PIPELINE**：实现真实 Species Name 到 Glyph Path 的转换和绘制。
8. **P2_RESULT_HANDOFF**：实现不可变 Snapshot、Consumer Validation、ACK 和 Result Stable。
9. **FULL_FLOW_INTEGRATION**：验证 P0 → P1 → P2 的同一照片、同一 Attempt、同一能量叙事。
10. **DEVICE_QA_AND_DELIVERY**：完成真机 GPU、视觉、性能和证据交付。

### 11.1 变更规则

允许优化：

- GPU 几何；
- Shader 和材质；
- 光学参数；
- Ribbon/Particle 数量；
- 速度、相位、振幅；
- 局部运动路径；
- 性能和内存管理。

不得擅自改变：

- Orb 是唯一 Task Energy 来源；
- 五条 Resident 与 Task Energy 的独立身份；
- P0 L1–L4 职责；
- KF01–KF06 顺序；
- P1 的真实数据驱动；
- 真实目标确认规则；
- P2 Stage 10–13 顺序和标称时间；
- 13 阶段 6,000ms 与模型等待 60,000ms 的冻结约束；
- Glyph 必须来自真实鱼种名称；
- Result 必须消费同一不可变 Snapshot；
- Consumer ACK 是 Result Stable 的前置条件。

若实现中发现合同与现有架构冲突，必须先输出：

- 冲突字段/模块；
- 现有源码证据；
- 对视觉、时间、数据和性能的影响；
- 建议方案；
- 是否需要用户批准。

不得静默改合同、伪造验收或另建平行系统。

---

# 12. 最终冻结清单

| 合同项 | 冻结内容 | 状态 |
|---|---|---|
| Global Story | Orb → Photo → Probe → Reveal → Result | DESIGN_FROZEN |
| Energy Identity | Orb Core 唯一能量源 | DESIGN_FROZEN |
| Resident Identity | G01/G02/A01/A02/C01 独立 | DESIGN_FROZEN |
| P0 | L1–L4 外围水光 | DESIGN_FROZEN |
| P0 Motion | KF01–KF06 | DESIGN_FROZEN |
| P1 | Internal Probe Filaments | DESIGN_FROZEN |
| P1 | N1/N2/N3 Recognition Nodes | DESIGN_FROZEN |
| P1 | Target Response Field | DESIGN_FROZEN |
| P1 Data | ROI/Target/Session/Attempt 驱动 | DESIGN_FROZEN |
| P2 | ORB_RETURN | DESIGN_FROZEN |
| P2 | ORB_TO_NAME | DESIGN_FROZEN |
| P2 | SPECIES_NAME_REVEAL | DESIGN_FROZEN |
| P2 | RESIDUE_SETTLE 13.1–13.5 | DESIGN_FROZEN |
| P2 Timing | 2,450ms 标称段落 | DESIGN_FROZEN |
| Global Timing | 13 Stage / 6,000ms / 60,000ms wait cap | DESIGN_FROZEN |
| Result Identity | Immutable Snapshot + Consumer ACK | DESIGN_FROZEN |
| Video Guide | 分段、连续性、Prompt、Negative Prompt | DESIGN_FROZEN |
| Development Guide | 复用现有 Runtime、统一时钟、GPU 验收 | DESIGN_FROZEN |

## 13. 明确排除项

本合同不包含：

- Tab 1 AI Orb 的重新设计；
- Water World V2.1 独立场景；
- 新识别模型训练；
- 新的鱼体分割算法；
- 重新生成照片素材；
- 重新设计 Result 页的信息架构；
- 生产代码修改；
- APK 构建；
- GPU 验收结果声明。

---

## 14. 最终产品定义

Tab 2 不是一个照片发光边框，也不是一个独立扫描动画。

它是一套由 AI Orb 驱动的、数据真实、视觉连续的照片识别体验：

**Orb 发出能量，水光包围照片，探测光丝进入照片，识别节点响应真实目标，能量回到 Orb，Orb 凝聚成真实鱼种名称，最终把同一张照片和同一个识别结果交给 Result 页面。**

这条因果链、能量身份、照片身份、识别身份和结果身份必须在视频生成与 Android/GLES 实现中保持一致。

**END OF CONTRACT — TAB2_AI_ORB_PHOTO_RECOGNITION_VISUAL_MOTION_CONTRACT_V1**
