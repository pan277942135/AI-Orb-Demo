# Water World V2.1 — Work 3.1 HUAWEI CMS-AL20 真机验收指南 (Hardware QA Guide)

**目标设备：** HUAWEI CMS-AL20 / Android 16 (API 36) / 预期 GPU：Maleoon 935（以真机 Diagnostics 实际读取为准）  
**待测安装包：** `AI-Orb-Demo-WaterWorld-V2.1-Work3.1-Core3D-debug.apk`  
**APK 大小：** `26,956,902 bytes` (`25.71 MiB`)  
**APK SHA-256：** `9a13be476f854376d7d8789a817e5c33854dc751100d251938eb7b992c7b6041`

---

## 一、安装与校验步骤

1. 校验 APK 文件 SHA-256 与上文一致（`shasum -a 256 AI-Orb-Demo-WaterWorld-V2.1-Work3.1-Core3D-debug.apk`）。
2. 安装到 HUAWEI CMS-AL20（覆盖安装或卸载旧 Debug 包后全新安装）：
   ```bash
   adb install -r AI-Orb-Demo-WaterWorld-V2.1-Work3.1-Core3D-debug.apk
   ```
3. 启动 App，先快速检查 **Tab 1（AI Orb）** 与 **Tab 2（Task / Orb）** 正常渲染且无闪烁/黑屏（确认零回归），再切换到 **Tab 3（Water World）**。

---

## 二、Tab 3 主画面动态视觉检查（抽屉保持折叠）

1. **启动与默认 UI 状态**
   - 诊断抽屉默认折叠，全屏呈现水晶球水下生态世界，无大面积调试浮层遮挡。
   - 球体进入后自动开始连续动态播放，无加载卡死、无闪退、无黑圆底/黑框。
2. **Core 3D 体积流体真实感检查（观察 15 秒以上）**
   - 水晶球下半部水体中可见连续、缓慢三维旋转且带 3D 卷曲流动的翡翠绿/碧青色半透明流体体积与柔和焦散，不是 2D 平面贴图旋转，也不是环形霓虹圈。
   - **无双层玻璃壳**：外层玻璃轮廓仅有一套清晰折射边，不存在第二层 AI Orb 玻璃高光叠影。
   - **无主体发白/过曝**：4 条游动的鱼、5 条环绕 Resident、2 株摆动水草、3 块底部岩石和 9 个上升气泡保持清晰层次与正确前后遮挡。
3. **动态播放控制检查**
   - 点击暂停：鱼、Resident、水草、气泡、水体与 Core 3D 体积同时定格，无时间撕裂。
   - 点击恢复：从断点无缝继续，无跳帧/位置瞬移。
   - 点击重置：立即回到 `T=0` 初始状态并重新播放。

---

## 三、展开诊断抽屉 — Section E 六项 Core 3D GPU 隔离模式验收

点击右上角/底部诊断入口展开抽屉，核对以下实测数据与 6 个隔离视图：

1. **Section C / Core 状态徽章核对**
   - `Core 3D Volume` 徽章显示 **`CORE_VOLUME_READY`**（若为 `CORE_VOLUME_ERROR` 则直接判定 FAIL）。
   - 核对实测字段：
     - `GL_RENDERER` / `GL_VERSION`（记录真机实际 Maleoon 935 / OpenGL ES 3.x 字符串）。
     - `volumeWidth × volumeHeight × volumeDepth = 256 × 256 × 32` (`GL_TEXTURE_3D`, `uploadGlStatus = GL_NO_ERROR`)。
     - `sampler3DUniformLocation >= 0`、`coreRotMatLocation >= 0`、`fluidPhaseLocation >= 0`、`coreRenderModeLocation >= 0`。
     - `rotationAngleDeg` 随时间以 `3.6°/s` 连续递增，`rotationMatDet ≈ 1.0000`，`rotationMatOrthoError < 1e-4`。
     - `glError = 0 (GL_NO_ERROR)`。
2. **Section E 六项 GPU 隔离按钮逐一点击截图**
   - `CORE_VOLUME_ONLY`：仅见球内三维流体体积，随时间真实三维旋转与流动。
   - `CORE_DENSITY`：清晰三维体积密度分布（非空、非全白、非单层切片）。
   - `CORE_FLOW`：半月面以下连续 3D 卷曲流场彩色方向分布。
   - `CORE_CAUSTICS`：球体下半部不规则流动焦散（无网格/蜂窝 Voronoi 伪影）。
   - `CORE_OPTICAL_DEPTH`：平滑的 Beer-Lambert 光学深度热力图。
   - `CORE_ROTATION`：旋转 3D Core-Frame 坐标条带随时间连续转动。
3. **切回 `FINAL` 模式**，确认完整 11 Pass + 5 Optics 画面恢复正常。

---

## 四、稳定性与 EGL Context 恢复测试

1. **连续播放 60 秒**：观察 FPS 稳定性、无持续掉帧、无 GL 错误累积、Native/Java Heap 无持续泄漏增长。
2. **前后台切换（EGL Context 恢复）**：切到手机桌面或锁屏 3 秒再返回 Tab 3，确认 `CORE_VOLUME_CONTEXT_LOST -> CORE_VOLUME_REUPLOADING -> CORE_VOLUME_READY` 自动恢复，Core 3D 体积继续正常渲染，无黑球/崩溃。
3. **Tab 1 / Tab 2 / Tab 3 来回切换 3 次**：确认三页切换流畅、无纹理串扰、无崩溃。

---

## 五、请回传的真机验收证据

完成上述检查后，请回传以下内容即可进入最终闭环判断：
1. Tab 3 主画面动态录屏（10–15 秒）或 `T=0s / T=6s / T=12s` 截图。
2. 展开诊断抽屉后的 Telemetry 截图（含 `GL_RENDERER`、`CORE_VOLUME_READY`、`256×256×32`、Uniform Location、`glError`）。
3. `Section E` 中至少 `CORE_VOLUME_ONLY` 与 `CORE_ROTATION` 两张隔离截图（建议 6 张全截）。
4. 60 秒连续运行与退后台恢复结论（PASS / 异常现象描述）。
