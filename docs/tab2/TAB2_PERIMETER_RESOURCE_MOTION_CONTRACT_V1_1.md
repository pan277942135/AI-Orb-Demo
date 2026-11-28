# TAB2 P0｜照片外围水光资源与动效合同 V1.1

**Contract ID:** TAB2_PERIMETER_RESOURCE_MOTION_CONTRACT_V1_1
**Status:** DESIGN_FROZEN
**Task ID:** TAB2_PERIMETER_RESOURCE_MOTION_CONTRACT_FREEZE_V1_1
**Repository:** pan277942135/AI-Orb-Demo
**Scope:** P0 照片外围水光 L1–L4 资源、几何、光学、KF01–KF06 动效与验收合同
**基线 Main:** 86c77d9781c9c88a56c98a0521d39647ae541a3d

---

## 1. 合同效力与依据

本合同只细化 P0 照片外围水光，为下一轮 AI Studio 视觉开发提供唯一资源与运动依据。

- 静态视觉唯一来源：冻结母版 PNG，且本轮重新验证了原始字节 SHA-256。
- 动态行为唯一来源：TAB2_AI_ORB_PHOTO_RECOGNITION_VISUAL_MOTION_CONTRACT_V1.md 的 3.3–3.6、9.2、9.3、10.1。
- 本文细化 L1–L4 资源独立性、照片路径、光学叠放、六个关键状态和 GPU 验收。
- 现有代码只用于记录实现映射与差距，不能反向改写冻结设计。
- 本轮未改动生产代码、母版 PNG、Lock JSON 或全局视觉合同。

### 状态术语

| 标记 | 含义 |
|---|---|
| MEASURED | 可直接从母版像素或仓库源码读取；图像坐标测量仍标注误差范围 |
| INFERRED | 依据可见合成效果或全局动效合同解释，不代表恢复了源材质或真实运动 |
| UNKNOWN | 静态 PNG 或源码静态读取不足以确认，必须由后续 GPU 证据验证 |

---

## 2. SOURCE LOCK

| 项目 | 锁定值 |
|---|---|
| Main 基线 | 86c77d9781c9c88a56c98a0521d39647ae541a3d |
| 唯一母版 | docs/tab2/assets/TAB2_P0_HIGH_FIDELITY_MASTER_V1.png |
| 实际下载大小 | 2,171,118 bytes |
| 实际图像尺寸 | 941 × 1672 px |
| 图像格式 | PNG / RGB；无 Alpha 通道 |
| 锁定 SHA-256 | a37819bba6b144ca7fec27ace55e5b319c41e67b89a11e70d51200bce8749d67 |
| 本轮重新计算 SHA-256 | a37819bba6b144ca7fec27ace55e5b319c41e67b89a11e70d51200bce8749d67 |
| Git Blob SHA-1 | a8d055b0dff734c3f8f9905b7b21cd57981c82d6 |
| Lock JSON | docs/tab2/P0_HIGH_FIDELITY_MASTER_V1.lock.json |
| 全局运动合同 | docs/tab2/TAB2_AI_ORB_PHOTO_RECOGNITION_VISUAL_MOTION_CONTRACT_V1.md |
| SOURCE LOCK 结果 | PASS |

PNG SHA-256 与指定值一致，故资源分析继续。Lock JSON 和全局合同仅作为只读输入；本轮不更新、不覆盖。

---

## 3. 母版视觉提取

### 3.1 照片几何

测量基于母版原始 941 × 1672 像素，坐标原点为左上角，X 向右、Y 向下。照片边缘带有白色发丝线、抗锯齿和外围水光，故边界是可复核的近似测量值，不等同于 Android 运行时 PhotoFrameGeometry。

| 项目 | 母版观测值 | 分类与误差 |
|---|---:|---|
| 照片可见边界 | 左约 93、上约 179、右约 858、下约 1079 px | MEASURED；边界约 ±5 px |
| 照片可见宽高 | 约 765 × 900 px | MEASURED；由上述边界估算 |
| 四角圆角半径 | 约 65 px | MEASURED；约 ±8 px，受光晕和边线影响 |
| 上边中心 | 约 (476, 179) px | MEASURED；约 ±5 px |
| 下边中心 | 约 (476, 1079) px | MEASURED；约 ±5 px |
| Orb 至照片连接轴 | 约 x=470 px | MEASURED；约 ±5 px |
| 水光外围带宽 | 长边外侧约 0–50 px，转角处局部更宽 | MEASURED；约 ±10 px，仅为合成画面可见范围 |

### 3.2 L1–L4 的可见结构

| 层 | 可直接观察到的视觉特征 | 不可从静态图确认的内容 |
|---|---|---|
| L1 冰蓝主水光 | 紧贴照片外缘的冰蓝、青蓝和冷白连续亮带；沿圆角及四边可见 | 原始 Ribbon 宽度、独立 Alpha、材质法线、真实速度 |
| L2 翡翠水雾 | 照片外侧较宽的青绿/翡翠色柔性区域，边缘呈波动、扩展和收束感 | 原始 Mesh 拓扑、雾化深度、独立 Alpha、相位差 |
| L3 香槟金细丝 | 与蓝绿层交叠的细窄暖金/白金高光；转角、底边中心与连接区域较亮 | 细丝数量、分支拓扑、折射率、真实运动方向 |
| L4 水滴与微光 | 照片外侧可见不同大小的圆形水滴/光点；部分也可能属于环境泡泡 | 粒子身份、总数、Billboard 几何、粒子生命周期和速度 |

母版是已经合成的 RGB 图像，不包含可分离 Alpha、法线、深度或材质通道。可见颜色是背景、照片边缘、发光层和压缩/色调映射共同形成的像素颜色；不得把这些像素值写成纯材质色或固定 Alpha。环境气泡与 L4 粒子可能重叠，图像不能单独证明每个气泡都属于 L4。

### 3.3 路径与空间关系

- 照片真实圆角边界是四层共用的空间基准；生产渲染须读取当前真实照片矩形，而非按屏幕固定位置画矩形。
- Orb 顶部到照片下边中心是独立的能量连接段；它接入同一 Task Energy 网络，不是外围闭环的一部分。
- 照片下边中心是到达及左右分流节点；两路沿底边向左右转角，再沿两侧上升，并在照片上边中心汇合。
- 上边汇合后，主能量必须沿外围继续传播并回到闭合路径，不能在顶部停住。
- 母版显示 KF06 完整水光构图，但它是静态画面；传播方向和 KF01–KF05 的因果顺序来自全局合同，不是从 PNG 推断的动画证据。

---

## 4. L1–L4 独立资源定义

四层是同一个 Orb Task Energy 逻辑网络中的四种可独立渲染资源身份。每层都必须能单独显示、关闭、提供独立 Alpha/几何诊断并参与最终合成；它们不能变成四个互不相关的能量源。

| Layer | 视觉职责 | 几何资源 | 材质与光学 | 运动资源 |
|---|---|---|---|---|
| L1 冰蓝主水光 | 定义主能量路径和传播脉冲 | 沿照片外侧真实圆角路径生成的 Ribbon/Mesh；左、右分支在路径节点连续连接 | 冰蓝、青蓝、冷白；有体积的半透明液态主体、细亮边和局部高光。实际 Alpha 与宽度由 GPU 对照校准，不从 PNG 猜值 | 唯一主传播脉冲；沿真实弧长前进，在底边中心分流、顶边中心汇合，进入连续闭环 |
| L2 翡翠水雾 | 形成外围水体体积与柔性扩散 | 独立柔性 Mesh/veil，围绕同一照片边界；不要求机械复制 L1 轮廓 | 半透明翡翠绿/青绿水雾；支持局部扩散、折叠、收束，须保持照片内容清晰 | 跟随 L1 的因果路径，但有独立相位和轻微滞后；不与 L1 完全重合 |
| L3 香槟金细丝 | 表现局部能量网络、分支与汇合 | 独立 Filament/Mesh；根据局部能量建立可变分支和交汇 | 香槟金、暖白的精细高光；只做局部强调，不能成为固定金色边框 | 多个细丝拥有异步相位和速度，可分支、汇合、局部增强及消散；数量不冻结 |
| L4 水滴粒子 | 补充微观折射、高光和水体细节 | 独立 Particle/Billboard 资源；粒子附着/采样同一外围路径或水体外缘 | 可变尺寸、亮度、折射和高光方向；稀疏且有层次，不得覆盖照片主体 | 沿水光方向运动；每个粒子可有独立速度、相位和生命周期；数量与位置不使用固定点阵 |

独立资源至少需要独立启停、材质参数入口、相位/速度入口和诊断输出。这里冻结的是资源职责和关系，不冻结具体 GPU 顶点布局、Draw Call 数量或未经证实的材质常量。

---

## 5. 几何、折射与合成合同

### 5.1 照片外围几何

- 以 PhotoFrameGeometry 的真实矩形与圆角为路径输入，沿弧长参数化。
- 两条半边路径在底边中心、顶部中心连续衔接，形成一条完整闭合外围路径。
- 所有水光带的主体几何须位于照片外侧。允许边缘抗锯齿贴近边界，不允许水光 Ribbon/Mesh 穿入照片内部。
- 圆角处的位置、切线与法线连续；不得在直线/圆弧连接处产生断点、尖角或突然变宽。
- 路径必须随真实照片布局更新，不得使用固定 BBox 或母版画面坐标作为生产运行坐标。

### 5.2 折射与透明度

- 照片纹理保持原始像素和识别身份；外围折射不得改写照片内部纹理。
- 如需折射，折射采样应作用于外围水光所处的背景/边缘环境；禁止产生 framebuffer 反馈采样。
- 各层使用独立 Alpha 和遮挡贡献进行合成；RGB 母版不提供可反解的真实 Alpha。
- Alpha 上限是允许值上限，不是固定输出 Alpha。输出 Alpha 由形状、相位、局部高光、遮罩及层合成共同决定。
- 禁止用 PNG 机械切成四份来冒充 L1–L4 独立 GPU 资源。

### 5.3 推荐 GPU 合成顺序

1. **L2 翡翠水雾**：先绘制较宽、较柔的体积层，建立水体深度。
2. **L1 冰蓝主水光**：叠加清楚的主传播形态和能量脉冲，保持主次关系。
3. **L3 香槟金细丝**：在水体和主流之上局部增强，保持纤细且可读。
4. **L4 水滴粒子**：最后绘制稀疏微观高光和折射边缘，不遮住照片内容。

使用透明混合并按上述前后关系保留遮挡；具体混合公式、Alpha 数值和折射强度由后续 GPU 验收对照母版确认。本顺序是资源合同，不表示当前 GPU 结果已经验证。

---

## 6. KF01–KF06 动效合同

所有关键帧共享全局合同指定的唯一运动时钟。关键帧是连续动画中的可取证状态，不是六张静态图或六个独立计时器。本合同不增加毫秒时间表。

| KF | 冻结状态 | 主运动行为 | L1–L4 响应 |
|---|---|---|---|
| KF01 | ORB_RELEASE | Orb Core 向照片发出同一个逻辑 Task Energy 主流 | L1 建立连接主流；L2 随流形成柔性体积；L3/L4 只从能量路径获得局部响应，不凭空生成 |
| KF02 | ARRIVAL | 主流抵达照片真实下边中心，形成短暂接触扩散 | L1 接触并连续承接路径；L2 局部柔性扩散；L3 局部高亮；L4 可在接触处形成稀疏水滴响应 |
| KF03 | BOTTOM_SPLIT | 能量从下边中心向左右两侧分流 | L1 左右分支速度/形态可有自然差异；L2 延迟跟随；L3 可变分支；L4 沿左右水光方向移动 |
| KF04 | SIDE_ASCEND | 两支沿照片左右真实圆角与侧边向上攀升 | L1 主流向上；L2 保持独立相位与柔性扩散；L3 异步细丝；L4 具有独立速度和生命周期 |
| KF05 | TOP_CONVERGE | 两路在照片上边中心连续汇合 | L1 合流后继续传播；L2 体积融合但保留自身相位；L3 局部交汇高亮；L4 响应汇合方向 |
| KF06 | PERIMETER_LOOP | 完整照片外围成为持续循环的单一水光网络 | L1 连续绕行；L2 延迟循环；L3 异步分支持续演化；L4 沿真实水光方向循环且独立更新 |

### 6.1 KF06 闭环连续性

- 闭环路径顺序为：下边中心 → 左下角 → 左侧 → 左上角 → 顶边汇合点 → 右上角 → 右侧 → 右下角 → 下边中心。
- 顶部汇合是路径上的连续节点；汇合后的能量必须继续绕行，不得停在顶部或瞬间跳回底边。
- 循环交界处的位置、切线速度、材质相位和能量强度连续。
- L1–L4 的几何和相位不得整体重置；允许各层内部持续异步变化。
- Orb 持续补充这一个逻辑能量网络；补充脉冲接入底边中心或已定义入口，不建立第二个 Orb 能量源。
- 循环路径可用连续弧长相位表达，但 seam 必须视觉无缝；不得把参数回绕造成的数学跳变暴露为画面跳变。
- 暂停和恢复继续使用同一时钟状态，恢复后不跳位、不重播整段。

---

## 7. 现有 V2.2 配置记录与代码映射

### 7.1 V2.2 材质参考

源码包含命名预设 PerimeterWaterLightMaterial.Tab2V22，但当前 Tab2WaterWorldSceneRenderer 并未直接使用该预设。当前运行构造只传入三个 Alpha scale，其他字段继承默认值：

| 参数 | 命名 Tab2V22 预设 | 当前 SceneRenderer 实例 |
|---|---:|---:|
| L1 Alpha scale | 0.96 | 0.95 |
| L2 Alpha scale | 0.86 | 0.85 |
| L3 Alpha scale | 0.92 | 0.90 |
| 每侧水滴数量参数 | 8 | 默认 11 |
| Ribbon width profile | TAB2_V22_SLENDER | 默认 LEGACY_6KF_FROZEN |
| L1 Alpha cap override | 0.30 | 默认 0，使用 frozen cap 0.38 |
| L2 Alpha cap override | 0.16 | 默认 0，使用 frozen cap 0.24 |
| L3 Alpha cap override | 0.28 | 默认 0，使用 frozen cap 0.50 |
| Direct display LDR | true | 默认 false |

源码路径：
- 命名预设及 Alpha cap：app/src/main/java/com/example/orb/task/PerimeterWaterLightRenderer.kt，约 17–74 行。
- 当前 SceneRenderer 参数：app/src/main/java/com/example/orb/task/Tab2WaterWorldSceneRenderer.kt，约 62–69 行。
- V2.2 width profile 上限：app/src/main/java/com/example/orb/task/PerimeterRibbonMeshBuilder.kt。

这些数值仅记录当前 Main 源码。Alpha scale 是进入 Shader 的比例因子；Alpha cap 是最终 Alpha 上限，不等于每个像素实际使用的 Alpha。当前 SceneRenderer 与命名 V2.2 预设不一致是本轮记录的差距；本轮不修改参数或生产调用点。

### 7.2 实际代码状态

| 资源/阶段 | Main 当前源码 | 存在与接通状态 | 当前差距 |
|---|---|---|---|
| Photo Path | PerimeterPathGeometry、PerimeterArcLengthTable、PhotoFrameGeometry、TaskSceneLayoutSnapshot | 几何存在；SceneRenderer 使用有效布局构造路径表 | 每侧路径从下边中心走到上边中心；闭环循环语义须额外接通并验证 |
| L1 | PerimeterRibbonMeshBuilder、PerimeterWaterLightRenderer、PERIMETER_RIBBON shader | 左右独立 Mesh strip、独立 layer id/Alpha scale/showL1；绘制调用已接通 | 主传播只有 shader 波动；没有证明闭合路径能量前沿连续循环 |
| L2 | 同上；evaluateEmeraldFlankMask | 独立 Mesh strip、独立 Alpha scale/showL2；绘制调用已接通 | 当前是参数化宽度与波动层；延迟相位不是独立 KF 事件/资源合同 |
| L3 | 同上；L3_GOLD_FILAMENT | 独立金色 strip、独立 Alpha scale/showL3；绘制调用已接通 | 当前每侧是单条 Ribbon，不是动态可变的分支/汇合细丝网络 |
| L4 | PerimeterWaterLightParticles、TASK_SPARKLE shader | 独立粒子绘制、showL4 与数量参数存在；绘制调用已接通 | 锚点数组固定，粒子数量受固定 seed 数量约束；未形成任意拓扑的独立生命周期资源 |
| KF01–KF05 | TaskEnergyController 含释放、接触、分流、攀升、顶部汇合阶段；Tab2IndependentPerimeterController 是当前 Tab2 活跃控制器 | 阶段逻辑在源码中存在，但未接入当前 Tab2 活跃外围渲染路径 | 当前控制器映射 releaseProgress=0、contactReached=false、contactIntensity=0；Orb 释放、照片到达和接触反馈无法由这一路触发 |
| KF06 | perimeterProgress、coverageComplete、PERIMETER_LOOP stage 与 Shader flowTime | 外围完整 reveal、Shader 流动和粒子位置变化存在 | 当前是完整静态覆盖上的视觉流动；未证明一个跨顶部与底边的闭合能量前沿、无缝 seam 或 Orb 同网补能 |
| GPU composite | Tab2WaterWorldSceneRenderer、PerimeterWaterLightRenderer | 有背景、Orb、水滴、照片、折射快照、L1–L4 绘制和透明混合代码 | 本轮未取得该 Main HEAD 的真机/GPU 关键帧、Alpha Heatmap 或连续循环证据；状态为 GPU_NOT_VERIFIED |
| 唯一运动时钟 | Tab2WaterWorldSceneRenderer 调用 motionRuntime.stepFrame；外围另由 Tab2IndependentPerimeterController.step(dt) 维护 elapsedSec/flowTimeSec | 两套状态均存在 | 活跃外围流未绑定统一 Motion Snapshot；须按全局合同收敛到既有单一运动时钟，不新增时钟 |

当前 Tab2IndependentPerimeterController 的 0.25s、0.55s 和 1.45s 是源码中的现状值，不是本合同的 KF 时间表，也不构成认可或建议。TaskEnergyController 中的时间值同样仅属于现有源码记录，不能作为本轮新冻结的视觉时长。

### 7.3 当前源文件定位

- app/src/main/java/com/example/orb/task/PerimeterPathGeometry.kt
- app/src/main/java/com/example/orb/task/PerimeterArcLengthTable.kt
- app/src/main/java/com/example/orb/task/PerimeterRibbonMeshBuilder.kt
- app/src/main/java/com/example/orb/task/PerimeterWaterLightRenderer.kt
- app/src/main/java/com/example/orb/task/Tab2IndependentPerimeterController.kt
- app/src/main/java/com/example/orb/task/Tab2WaterWorldSceneRenderer.kt
- app/src/main/java/com/example/orb/task/TaskEnergyController.kt
- app/src/main/java/com/example/orb/task/PhotoFrameGeometry.kt
- app/src/main/java/com/example/orb/task/TaskSceneLayoutSnapshot.kt
- app/src/main/java/com/example/orb/task/TaskWaterLightShaders.kt

---

## 8. 独立分层与后续 GPU 验收

### 8.1 五种隔离输出

后续实现必须提供同一照片布局、同一运动时钟下的独立渲染输出：

1. L1 Only
2. L2 Only
3. L3 Only
4. L4 Only
5. L1–L4 Composite

每个输出都能独立启用/关闭并记录资源状态。隔离模式不能改变照片几何、路径、时钟或生产状态。

### 8.2 必须提交的验收证据

- L1、L2、L3、L4 四层各自的 GPU 输出。
- 四层各自的真实 Alpha Heatmap；标明诊断时读取的是 Shader 实际输出 Alpha。
- KF01–KF06 六帧截图，关联相同 Photo、Session/Attempt 与运动时钟。
- KF06 连续流动视频，覆盖完整闭环并经过循环交界；证明位置、方向、速度、相位连续，无整体重置。
- 最终 L1–L4 GPU 合成画面。
- GPU Vendor、Renderer、GLES 版本、Shader 编译/链接结果和证据帧时间戳。
- 照片原图/纹理身份与外围渲染前后对照，证明照片内容未被改写。

每项证据必须标记真实设备 GPU、模拟器 GPU 或软件渲染。静态 SVG、PNG 母版、Shader 源码、CPU 预览和单元测试均不能替代 GPU 视觉验收。本合同未生成或宣称任何 GPU 验收结果。

---

## 9. 下游实施约束

下一轮实现按本合同直接实施，不重新分析资源身份或另建运动语义：

- 使用同一真实照片边界、同一 Task Energy 网络、同一条闭合外围路径。
- 让 L1–L4 分别拥有可控几何、材质、光学与运动资源。
- 沿用全局合同规定的唯一运动时钟；KF01–KF06 由同一连续状态推进。
- 使用真实圆角与连续弧长路径；不使用固定矩形、固定 BBox、硬切 PNG 或固定粒子阵列冒充动态资源。
- 保持照片像素和照片身份不变；折射与高光不能污染照片内部。
- 以第 8 节证据完成 GPU 验收后，才能标记 IMPLEMENTED / GPU_VERIFIED / VISUAL_ACCEPTED。
- 本合同中的当前代码差距是下游待处理事项；本轮冻结过程不修改它们。

**END OF CONTRACT — TAB2_PERIMETER_RESOURCE_MOTION_CONTRACT_V1_1**
