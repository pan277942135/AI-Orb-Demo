# Tab1 正式渲染调用链审计

## 启动与上下文恢复

1. `MainActivity.kt:342–368` 渲染三个 Tab 入口；`DemoTab.kt:8–12` 声明三个枚举。
2. `AIOrbViewModel.kt:41–55` 把 `AI_ORB` 映射到 `OrbSceneMode.ORB_ONLY`。
3. `AIOrbCanvas.kt:103–111` 创建 ES3 `GLSurfaceView`，8-bit RGBA/16-bit depth，保留 pause context，continuous render。
4. `AIOrbScene.onSurfaceCreated`（`AIOrbScene.kt:428–459`）初始化 `AIOrbCore` 和共用场景资源；Context 重建时先清旧 GL 名称再创建新资源。
5. `AIOrbCore.init` 委托 `OrbFluidVolumeRenderer.init`；后者编译生产 `CORE_GLASS_VERT/FRAG`，创建 Icosphere，并启动 WebP 异步解码。
6. `OrbVolumeTexture3D.uploadIfReady/bindTo` 在 GL 线程上传和绑定体积、Detail、Caustics、Glass Environment 纹理。

## 正式 FULL_RENDER

`AIOrbScene.onDrawFrame` 更新/冻结 motion snapshot，读取唯一 Master Camera；当生产模式的 V3 资产尚未 Ready 时输出背景启动帧并发布 `LOADING`/`V3_ASSET_ERROR`，不使用程序化体积冒充正式画面（`AIOrbScene.kt:609–627`）。随后：

1. Background 与远景粒子。
2. Back Resident ribbon pass（pass 0）。
3. Back energy highlights。
4. `captureRefractionSnapshot()` 将当前 Scene FBO blit 到 Refraction FBO。
5. `AIOrbCore.draw()` → `OrbFluidVolumeRenderer.draw()` → 生产 Shader / 3D raymarch。
6. Front Resident pass（pass 1）、高光与粒子。
7. Bloom / ACES / sRGB post-process。
8. FBO 捕获写出固定快照图与 runtime telemetry。

对应代码：`AIOrbScene.kt:727–789`（相机和 flags）、`813–840`（后景与折射快照）、`842–879`（Core）、`881–913`（前景与后处理）。

## Capture 所证明的运行状态

- Exact HEAD `fb04598709a282d3818745c07b0a71897cccfd66`
- Android API 36 x86_64 AVD；后端 SwiftShader software GLES 3.0
- Shader Program 6；Shader SHA `4fc2be7ecf52b94c44e7a25b8472c959b100141f8622e3abb6626ff96bc26014`
- Volume 256×256×32；`GL_SRGB8_ALPHA8`; `uVolumeLoaded=1`; 4 textures valid
- actual raymarch 20 steps（software cap），HIGH configured steps 48
- Camera snapshot `v32_snap_20261124T104318918Z`, 1254×1254
- FBO complete，`GL_NO_ERROR`

来源：`evidence/runtime/CAPTURE_MANIFEST.json`, `RUNTIME_RENDER_CONFIG.json`, `RUNTIME_TELEMETRY.json`, `BUILD_FINGERPRINT.json`。

## 捕获模式清单的实现偏差

`AIOrbScene.kt:788–789` 的实际 Tab1 draw flags 在 `RESIDENTS_ONLY` 中关闭 Core；但 `activeRenderPassesV3()` 在 `AIOrbScene.kt:1296–1310` 对 V3 的 `drawCore` 条件没有排除 `RESIDENTS_ONLY`，`drawHighlights` 也没有排除它。因此该诊断模式的源代码 pass manifest 会多报 Core 和后景 highlights。`OrbGpuReferenceCapture` 的捕获清单另有 `coreShaderExecuted` 实际值，且本次 capture 的 `RESIDENTS_ONLY` 正确没有执行 Core。它是可复现的 telemetry/manifest 准确性缺陷，不是 FULL_RENDER 渲染路径降级证据。
