package com.example.waterworld

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Collapsible Developer Diagnostics bottom drawer for [WaterWorldPreviewActivity].
 *
 * Strictly COLLAPSED (`View.GONE`) by default so end users enter directly into the unobstructed
 * Water World V2.1 showcase scene. Expanding or collapsing this drawer never mutates the active
 * render mode or alters the displayed FINAL_SCENE output.
 */
internal class WaterWorldDiagnosticsDrawer(
    private val context: Context,
    private val onSelectDiagnosticMode: (WaterWorldRenderGraph.DiagnosticMode) -> Unit,
    private val onRestoreFinalScene: () -> Unit,
    private val onTriggerEvidenceExport: () -> Unit,
    private val onSaveEvidenceZip: () -> Unit = {},
    private val onTogglePlayPause: () -> Unit = {},
    private val onResetTimeline: () -> Unit = {},
    private val onToggleStaticReference: () -> Unit = {},
    private val onCycleSpeedScale: () -> Unit = {},
    private val onTriggerDynamicEvidenceExport: () -> Unit = {},
) {
    internal companion object {
        const val TAG_DIAGNOSTICS_TOGGLE = "water_world_diagnostics_toggle"
        const val TAG_DIAGNOSTICS_PANEL = "water_world_diagnostics_panel"
        const val TAG_SECTION_MOTION = "water_world_diag_section_motion"
        const val TAG_MOTION_STATUS = "water_world_diag_motion_status"
        const val TAG_PLAY_PAUSE_BUTTON = "water_world_diag_play_pause"
        const val TAG_RESET_BUTTON = "water_world_diag_reset"
        const val TAG_STATIC_REF_BUTTON = "water_world_diag_static_ref"
        const val TAG_SPEED_TOGGLE_BUTTON = "water_world_diag_speed_toggle"
        const val TAG_EXPORT_DYNAMIC_BUTTON = "water_world_export_dynamic_button"
        const val TAG_SECTION_RENDER_PASS = "water_world_diag_section_render_pass"
        const val TAG_SECTION_OPTICAL = "water_world_diag_section_optical"
        const val TAG_SECTION_CORE_3D = "water_world_diag_section_core_3d"
        const val TAG_CORE_STATUS = "water_world_diag_core_status"
        const val TAG_CORE_VOLUME_ONLY = "water_world_diag_core_volume_only"
        const val TAG_CORE_DENSITY = "water_world_diag_core_density"
        const val TAG_CORE_FLOW = "water_world_diag_core_flow"
        const val TAG_CORE_CAUSTICS = "water_world_diag_core_caustics"
        const val TAG_CORE_OPTICAL_DEPTH = "water_world_diag_core_optical_depth"
        const val TAG_CORE_ROTATION = "water_world_diag_core_rotation"
        const val TAG_SECTION_GPU = "water_world_diag_section_gpu"
        const val TAG_SECTION_EXPORT = "water_world_diag_section_export"
        const val TAG_OPTICAL_GLASS = "water_world_diag_optical_glass"
        const val TAG_OPTICAL_WATER = "water_world_diag_optical_water"
        const val TAG_OPTICAL_CAUSTICS = "water_world_diag_optical_caustics"
        const val TAG_OPTICAL_BLOOM = "water_world_diag_optical_bloom"
        const val TAG_OPTICAL_BOKEH = "water_world_diag_optical_bokeh"
        const val TAG_RESTORE_FINAL_SCENE = "water_world_diag_restore_final_scene"
        const val TAG_EXPORT_BUTTON = "water_world_export_button"
        const val TAG_SAVE_ZIP_BUTTON = "water_world_save_zip_button"
        const val TAG_EXPORT_SUMMARY = "water_world_diag_export_summary"
        const val TAG_GPU_INFO = "water_world_diag_gpu_info"
        const val TAG_PASS_LIST = "water_world_diag_pass_list"
        const val TAG_PASS_PNG_PREVIEW = "water_world_diag_pass_png_preview"
        const val TAG_PASS_PNG_LABEL = "water_world_diag_pass_png_label"
    }

    val rootContainer: LinearLayout
    val toggleButton: Button
    val panelContainer: LinearLayout
    val exportButton: Button
    val exportDynamicButton: Button
    val saveZipButton: Button
    val restoreFinalButton: Button
    val playPauseButton: Button
    val resetButton: Button
    val staticRefButton: Button
    val speedToggleButton: Button

    private val motionStatusView: TextView
    private val coreStatusView: TextView
    private val passListContainer: LinearLayout
    private val passPreviewCard: LinearLayout
    private val passPreviewLabel: TextView
    private val passPreviewImage: ImageView
    private val gpuInfoView: TextView
    private val exportSummaryView: TextView
    private val opticalButtons = linkedMapOf<WaterWorldRenderGraph.DiagnosticMode, Button>()
    private var previewBitmap: Bitmap? = null
    private var latestExportDir: File? = null
    private var lastTelemetry: JSONObject? = null
    private var currentMode: WaterWorldRenderGraph.DiagnosticMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE

    var isExpanded: Boolean = false
        private set

    init {
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }

        toggleButton = Button(context).apply {
            tag = TAG_DIAGNOSTICS_TOGGLE
            text = context.getString(R.string.water_world_diagnostics_collapsed)
            contentDescription = context.getString(R.string.water_world_diagnostics_collapsed)
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor("#E6D9F7FF"))
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#B8051C26"), Color.parseColor("#6638C5D8"), dp(22))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { toggleExpanded() }
        }

        panelContainer = LinearLayout(context).apply {
            tag = TAG_DIAGNOSTICS_PANEL
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE // Strictly COLLAPSED by default
            background = pillBackground(Color.parseColor("#EB04151E"), Color.parseColor("#8036B8C9"), dp(20))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val maxDrawerHeight = (context.resources.displayMetrics.heightPixels * 0.52f).toInt().coerceAtLeast(dp(260))
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
        }
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top action bar inside expanded drawer: Restore FINAL_SCENE
        restoreFinalButton = Button(context).apply {
            tag = TAG_RESTORE_FINAL_SCENE
            text = context.getString(R.string.water_world_restore_final_scene)
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC0E7E70"), Color.parseColor("#995CE6C8"), dp(16))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onRestoreFinalScene() }
        }
        scrollContent.addView(
            restoreFinalButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        )

        // Section M: Work 3 Dynamic Playback Runtime Controls
        val sectionM = createSectionCard(TAG_SECTION_MOTION, "M. Dynamic Runtime Playback (Work 3)")
        motionStatusView = TextView(context).apply {
            tag = TAG_MOTION_STATUS
            text = "PLAYING · T=0.00s · 1.00x"
            setTextColor(Color.parseColor("#E6DFF7FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPauseButton = Button(context).apply {
            tag = TAG_PLAY_PAUSE_BUTTON
            text = "暂停"
            contentDescription = "暂停或继续动态播放"
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC0E7E90"), Color.parseColor("#8063DCEB"), dp(12))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onTogglePlayPause() }
        }
        resetButton = Button(context).apply {
            tag = TAG_RESET_BUTTON
            text = "重置 T=0"
            contentDescription = "重置时间轴到 T=0"
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#BF0B4254"), Color.parseColor("#6638C5D8"), dp(12))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onResetTimeline() }
        }
        speedToggleButton = Button(context).apply {
            tag = TAG_SPEED_TOGGLE_BUTTON
            text = "速度 1.0x"
            contentDescription = "切换动态播放速度"
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#BF0B4254"), Color.parseColor("#6638C5D8"), dp(12))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onCycleSpeedScale() }
        }
        row1.addView(playPauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row1.addView(resetButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row1.addView(speedToggleButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        staticRefButton = Button(context).apply {
            tag = TAG_STATIC_REF_BUTTON
            text = "切换静态参考 T=0 (回归对比)"
            contentDescription = "切换静态参考 T=0 模式"
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#BF104D5A"), Color.parseColor("#665CE6C8"), dp(12))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onToggleStaticReference() }
        }
        sectionM.addView(motionStatusView)
        sectionM.addView(row1)
        sectionM.addView(
            staticRefButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        scrollContent.addView(sectionM)

        // Section A: Render Pass Diagnostics (11 Passes)
        val sectionA = createSectionCard(TAG_SECTION_RENDER_PASS, "A. Render Pass Diagnostics (11 Passes)")
        passListContainer = LinearLayout(context).apply {
            tag = TAG_PASS_LIST
            orientation = LinearLayout.VERTICAL
        }
        sectionA.addView(passListContainer)

        passPreviewCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = pillBackground(Color.parseColor("#CC021018"), Color.parseColor("#6648D2E8"), dp(12))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        passPreviewLabel = TextView(context).apply {
            tag = TAG_PASS_PNG_LABEL
            setTextColor(Color.parseColor("#FFD9F8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        passPreviewImage = ImageView(context).apply {
            tag = TAG_PASS_PNG_PREVIEW
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Intermediate Pass PNG Preview"
        }
        val closePreviewBtn = Button(context).apply {
            text = "关闭中间 PNG 预览"
            isAllCaps = false
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#990A3E4E"), Color.parseColor("#6638C5D8"), dp(12))
            setOnClickListener { clearPassPngPreview() }
        }
        passPreviewCard.addView(passPreviewLabel)
        passPreviewCard.addView(
            passPreviewImage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180),
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )
        passPreviewCard.addView(closePreviewBtn)
        sectionA.addView(
            passPreviewCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        )
        scrollContent.addView(sectionA)

        // Section B: Optical Diagnostics (5 Independent Optical Nodes)
        val sectionB = createSectionCard(TAG_SECTION_OPTICAL, "B. Optical Diagnostics (5 Optics)")
        val opticalEntries = listOf(
            Triple(TAG_OPTICAL_GLASS, "1. Glass Refraction", WaterWorldRenderGraph.DiagnosticMode.OPTICAL_GLASS_REFRACTION),
            Triple(TAG_OPTICAL_WATER, "2. Water Normal Distortion", WaterWorldRenderGraph.DiagnosticMode.OPTICAL_WATER_DISTORTION),
            Triple(TAG_OPTICAL_CAUSTICS, "3. Caustics", WaterWorldRenderGraph.DiagnosticMode.OPTICAL_CAUSTICS),
            Triple(TAG_OPTICAL_BLOOM, "4. Bloom", WaterWorldRenderGraph.DiagnosticMode.OPTICAL_BLOOM),
            Triple(TAG_OPTICAL_BOKEH, "5. Bokeh", WaterWorldRenderGraph.DiagnosticMode.OPTICAL_BOKEH),
        )
        for ((tagId, label, mode) in opticalEntries) {
            val btn = Button(context).apply {
                tag = tagId
                text = label
                isAllCaps = false
                minHeight = dp(48)
                minimumHeight = dp(48)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = pillBackground(Color.parseColor("#BF0B4254"), Color.parseColor("#6638C5D8"), dp(12))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { onSelectDiagnosticMode(mode) }
            }
            opticalButtons[mode] = btn
            sectionB.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            )
        }
        scrollContent.addView(sectionB)

        // Section E: Core 3D Volume GPU Isolation (Work 3.1 — 32 Z-Slice GL_TEXTURE_3D)
        val sectionCore3d = createSectionCard(TAG_SECTION_CORE_3D, "E. Core 3D Volume GPU Isolation (32 Z-Slices)")
        coreStatusView = TextView(context).apply {
            tag = TAG_CORE_STATUS
            text = "3D Volume: CORE_VOLUME_LOADING · 256x256x32 GL_TEXTURE_3D"
            setTextColor(Color.parseColor("#E6DFF7FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        }
        sectionCore3d.addView(coreStatusView)
        val coreEntries = listOf(
            Triple(TAG_CORE_VOLUME_ONLY, "1. CORE_VOLUME_ONLY (纯 3D 体积)", WaterWorldRenderGraph.DiagnosticMode.CORE_VOLUME_ONLY),
            Triple(TAG_CORE_DENSITY, "2. CORE_DENSITY (3D 密度场)", WaterWorldRenderGraph.DiagnosticMode.CORE_DENSITY),
            Triple(TAG_CORE_FLOW, "3. CORE_FLOW (3D Curl 流场)", WaterWorldRenderGraph.DiagnosticMode.CORE_FLOW),
            Triple(TAG_CORE_CAUSTICS, "4. CORE_CAUSTICS (3D 焦散)", WaterWorldRenderGraph.DiagnosticMode.CORE_CAUSTICS),
            Triple(TAG_CORE_OPTICAL_DEPTH, "5. CORE_OPTICAL_DEPTH (光学深度)", WaterWorldRenderGraph.DiagnosticMode.CORE_OPTICAL_DEPTH),
            Triple(TAG_CORE_ROTATION, "6. CORE_ROTATION (3D 自转方向)", WaterWorldRenderGraph.DiagnosticMode.CORE_ROTATION),
        )
        for ((tagId, label, mode) in coreEntries) {
            val btn = Button(context).apply {
                tag = tagId
                text = label
                contentDescription = label
                isAllCaps = false
                minHeight = dp(48)
                minimumHeight = dp(48)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = pillBackground(Color.parseColor("#BF0B4254"), Color.parseColor("#6638C5D8"), dp(12))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { onSelectDiagnosticMode(mode) }
            }
            opticalButtons[mode] = btn
            sectionCore3d.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            )
        }
        scrollContent.addView(sectionCore3d)

        // Section C: GPU Diagnostics
        val sectionC = createSectionCard(TAG_SECTION_GPU, "C. GPU Diagnostics")
        gpuInfoView = TextView(context).apply {
            tag = TAG_GPU_INFO
            setTextColor(Color.parseColor("#E6DFF7FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        sectionC.addView(gpuInfoView)
        scrollContent.addView(sectionC)

        // Section D: Evidence Export
        val sectionD = createSectionCard(TAG_SECTION_EXPORT, "D. Evidence Export")
        exportButton = Button(context).apply {
            tag = TAG_EXPORT_BUTTON
            text = context.getString(R.string.water_world_export_button)
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC0D7A6E"), Color.parseColor("#995CE6C8"), dp(14))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onTriggerEvidenceExport() }
        }
        saveZipButton = Button(context).apply {
            tag = TAG_SAVE_ZIP_BUTTON
            text = "保存 Evidence ZIP 到系统文件 (SAF)"
            contentDescription = "保存 Evidence ZIP 到系统文件"
            isAllCaps = false
            isEnabled = false
            alpha = 0.55f
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC0E5C82"), Color.parseColor("#8063DCEB"), dp(14))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onSaveEvidenceZip() }
        }
        exportSummaryView = TextView(context).apply {
            tag = TAG_EXPORT_SUMMARY
            setTextColor(Color.parseColor("#CCE3F8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(8), 0, 0)
            text = "包含：11 Pass + 5 Optics + final_fbo.png + android_screen_capture.png、ANDROID_GPU_TELEMETRY.json、SCREEN_VS_FBO_DIFF.json、EVIDENCE_SHA256_MANIFEST.json 与零权限 SAF ZIP 导出。导出后自动恢复 FINAL_SCENE。"
        }
        exportDynamicButton = Button(context).apply {
            tag = TAG_EXPORT_DYNAMIC_BUTTON
            text = "导出 Work 3 动态证据 (T=0,1,2,4,8,12s)"
            contentDescription = "导出 Work 3 动态多时间点证据包"
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC147D8E"), Color.parseColor("#9963DCEB"), dp(14))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onTriggerDynamicEvidenceExport() }
        }
        sectionD.addView(exportButton)
        sectionD.addView(
            exportDynamicButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        )
        sectionD.addView(
            saveZipButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        )
        sectionD.addView(exportSummaryView)
        scrollContent.addView(sectionD)

        scrollView.addView(scrollContent)
        panelContainer.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxDrawerHeight,
            )
        )

        rootContainer.addView(
            toggleButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )
        rootContainer.addView(
            panelContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        )

        // Populate canonical 11-pass rows & initial GPU placeholders
        renderPassRows(null)
        updateGpuDiagnostics(WaterWorldPreviewActivity.PageState.LOADING, null, null)
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        panelContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        val labelRes = if (expanded) {
            R.string.water_world_diagnostics_expanded
        } else {
            R.string.water_world_diagnostics_collapsed
        }
        toggleButton.text = context.getString(labelRes)
        toggleButton.contentDescription = context.getString(labelRes)
    }

    fun toggleExpanded() {
        setExpanded(!isExpanded)
    }

    fun updateActiveMode(mode: WaterWorldRenderGraph.DiagnosticMode) {
        currentMode = mode
        for ((entryMode, btn) in opticalButtons) {
            val selected = entryMode == mode
            btn.background = if (selected) {
                pillBackground(Color.parseColor("#E6167E96"), Color.parseColor("#FF7AEBFF"), dp(12))
            } else {
                pillBackground(Color.parseColor("#BF0B4254"), Color.parseColor("#6638C5D8"), dp(12))
            }
        }
        restoreFinalButton.alpha = if (mode == WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE) 0.72f else 1.0f
    }

    fun updateTelemetry(
        pageState: WaterWorldPreviewActivity.PageState,
        telemetry: JSONObject?,
        errorDetail: String?,
    ) {
        if (telemetry != null) {
            lastTelemetry = telemetry
        }
        renderPassRows(lastTelemetry)
        updateGpuDiagnostics(pageState, lastTelemetry, errorDetail)
    }

    fun updateMotionStatus(runtime: WaterWorldMotionRuntime) {
        motionStatusView.text = runtime.statusSummaryText()
        playPauseButton.text = if (runtime.isPlaying) "暂停" else "继续"
        speedToggleButton.text = String.format(Locale.US, "速度 %.1fx", runtime.speedScale)
        staticRefButton.text = if (runtime.staticReferenceMode) {
            "当前: 静态参考 T=0 · 点击恢复动态"
        } else {
            "切换静态参考 T=0 (回归对比)"
        }
    }

    fun updateExportResult(
        outputDir: File?,
        statusMessage: String,
        inFlight: Boolean,
        zipReady: Boolean = false,
    ) {
        if (outputDir != null) {
            latestExportDir = outputDir
        }
        exportButton.isEnabled = !inFlight
        exportDynamicButton.isEnabled = !inFlight
        saveZipButton.isEnabled = !inFlight && zipReady
        saveZipButton.alpha = if (saveZipButton.isEnabled) 1.0f else 0.55f
        exportSummaryView.text = statusMessage
        renderPassRows(lastTelemetry)
    }

    fun release() {
        clearPassPngPreview()
    }

    private fun renderPassRows(telemetry: JSONObject?) {
        passListContainer.removeAllViews()
        val recordById = HashMap<String, JSONObject>()
        val passArr: JSONArray? = telemetry?.optJSONArray("passRecords")
        if (passArr != null) {
            for (i in 0 until passArr.length()) {
                val obj = passArr.optJSONObject(i) ?: continue
                recordById[obj.optString("passId")] = obj
            }
        }

        for (stage in WaterWorldRenderGraph.canonical11StageGraph()) {
            val wireId = stage.passId.wireId
            val rec = recordById[wireId]
            val executed = rec != null
            val drawCount = rec?.optInt("drawCount", 0) ?: 0
            val fboComplete = rec?.optBoolean("fboComplete", false) ?: false
            val elapsedMs = rec?.optDouble("elapsedCpuMs", Double.NaN) ?: Double.NaN
            val msStr = if (elapsedMs.isFinite()) String.format(Locale.US, "%.2fms", elapsedMs) else "NOT_MEASURED"
            val exportedPng = latestExportDir?.let { File(it, "${wireId.lowercase(Locale.US)}.png") }
            val hasExportedPng = exportedPng?.isFile == true
            val livePassMode = liveDiagnosticModeForPass(stage.passId)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = pillBackground(Color.parseColor("#8006222E"), Color.parseColor("#3348C9DC"), dp(10))
            }

            val info = TextView(context).apply {
                val statusTag = if (executed) "OK" else "PENDING"
                val fboTag = if (executed) (if (fboComplete) "FBO:OK" else "FBO:FAIL") else "FBO:-"
                text = "$wireId · $statusTag · Draw:$drawCount · $fboTag · $msStr"
                setTextColor(if (executed) Color.parseColor("#E6E6FBFF") else Color.parseColor("#99B8D7E2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
                typeface = Typeface.MONOSPACE
            }

            row.addView(
                info,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            )

            if (hasExportedPng || livePassMode != null) {
                val previewBtn = Button(context).apply {
                    text = if (hasExportedPng) "PNG预览" else "GPU预览"
                    isAllCaps = false
                    minHeight = dp(48)
                    minimumHeight = dp(48)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTextColor(Color.WHITE)
                    background = pillBackground(Color.parseColor("#BF0E5B6E"), Color.parseColor("#805CE6C8"), dp(10))
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener {
                        if (exportedPng != null && exportedPng.isFile) {
                            showPassPngPreview(wireId, exportedPng)
                        } else if (livePassMode != null) {
                            onSelectDiagnosticMode(livePassMode)
                        }
                    }
                }
                row.addView(previewBtn)
            }

            passListContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
            )
        }
    }

    private fun liveDiagnosticModeForPass(passId: WaterWorldRenderGraph.PassId): WaterWorldRenderGraph.DiagnosticMode? =
        when (passId) {
            WaterWorldRenderGraph.PassId.RESIDENT_BACK -> WaterWorldRenderGraph.DiagnosticMode.PASS_RESIDENT_BACK
            WaterWorldRenderGraph.PassId.FISH -> WaterWorldRenderGraph.DiagnosticMode.PASS_FISH
            WaterWorldRenderGraph.PassId.RESIDENT_FRONT -> WaterWorldRenderGraph.DiagnosticMode.PASS_RESIDENT_FRONT
            else -> null
        }

    private fun showPassPngPreview(wireId: String, file: File) {
        clearPassPngPreview()
        val decoded = BitmapFactory.decodeFile(file.path) ?: return
        previewBitmap = decoded
        passPreviewLabel.text = "中间 Pass PNG 预览: $wireId (${decoded.width}x${decoded.height})"
        passPreviewImage.setImageBitmap(decoded)
        passPreviewCard.visibility = View.VISIBLE
    }

    private fun clearPassPngPreview() {
        passPreviewImage.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        passPreviewCard.visibility = View.GONE
    }

    private fun updateGpuDiagnostics(
        pageState: WaterWorldPreviewActivity.PageState,
        telemetry: JSONObject?,
        errorDetail: String?,
    ) {
        val vendor = telemetry?.optString("glVendor").orEmpty().ifBlank { "NOT_INITIALIZED" }
        val renderer = telemetry?.optString("glRenderer").orEmpty().ifBlank { "NOT_INITIALIZED" }
        val glesVer = telemetry?.optString("glVersion").orEmpty().ifBlank { "OpenGL ES 3.0 (pending)" }
        val shaderStatus = when {
            pageState == WaterWorldPreviewActivity.PageState.SHADER_FAILED -> "FAILED (${errorDetail ?: "compile/link error"})"
            telemetry?.optBoolean("shaderCompileLinkOk", false) == true -> {
                val cnt = telemetry.optInt("shaderProgramCount", 12)
                "PASS (8 shader groups / $cnt GL programs)"
            }
            else -> "PENDING"
        }
        val drawCount = if (telemetry != null) telemetry.optInt("drawCount", 0).toString() else "0"
        val passCount = if (telemetry != null) "${telemetry.optInt("passCount", 0)} / 11" else "0 / 11"
        val opticalCount = if (telemetry != null) "${telemetry.optInt("opticalNodeCount", 0)} / 5" else "0 / 5"
        val gpuFrameTime = telemetry?.optString("gpuFrameTimeMs").orEmpty().ifBlank { "NOT_MEASURED" }
        val cpuFrameTime = telemetry?.optDouble("cpuFrameTimeMs", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.2f ms", it) }
            ?: "NOT_MEASURED"
        val gpuClass = telemetry?.optString("rendererClass").orEmpty().ifBlank { "UNCLASSIFIED_PENDING_GL" }
        val totalVramMb = telemetry?.optDouble("estimatedTotalGpuMemoryMb", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f MB (FBO %.1f + Tex %.1f)", it, telemetry.optDouble("estimatedFboMemoryMb", 0.0), telemetry.optDouble("estimatedTextureMemoryMb", 0.0)) }
            ?: "NOT_MEASURED"
        val stageMs = telemetry?.optDouble("resourceStagingMs", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f ms", it) }
            ?: "NOT_MEASURED"
        val firstFrameMs = telemetry?.optDouble("firstFramePresentedMs", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f ms", it) }
            ?: "NOT_MEASURED"
        val vpObj = telemetry?.optJSONObject("presentViewport")
        val vpStr = if (vpObj != null) {
            "L=${vpObj.optInt("left")} B=${vpObj.optInt("bottom")} S=${vpObj.optInt("side")} (${vpObj.optInt("surfaceWidth")}x${vpObj.optInt("surfaceHeight")})"
        } else {
            "PENDING"
        }
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"

        val coreState = telemetry?.optString("coreVolumeLifecycleState").orEmpty().ifBlank { "CORE_VOLUME_LOADING" }
        val coreEnabled = telemetry?.optBoolean("core3DVolumeEnabled", false) ?: false
        val coreObj = telemetry?.optJSONObject("core3dGpuInspection")
        val coreTexId = coreObj?.optInt("volumeTextureId", 0) ?: 0
        val coreSamplerLoc = coreObj?.optInt("sampler3DUniformLocation", -1) ?: -1
        val coreRotLoc = coreObj?.optInt("coreRotMatLocation", -1) ?: -1
        val coreDims = if (coreObj != null && coreObj.optInt("volumeDepth", 0) > 0) {
            "${coreObj.optInt("volumeWidth", 256)}x${coreObj.optInt("volumeHeight", 256)}x${coreObj.optInt("volumeDepth", 32)}"
        } else {
            "256x256x32"
        }
        val coreUploadMs = coreObj?.optDouble("uploadTimeMs", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.2f ms", it) }
            ?: "PENDING"
        val coreVramMb = telemetry?.optDouble("estimatedCore3dVolumeMemoryMb", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.2f MB", it) }
            ?: "0.00 MB"
        val coreDet = telemetry?.optDouble("coreRotationMatDet", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.5f", it) }
            ?: "1.00000"
        val nativeHeapMb = telemetry?.optDouble("nativeHeapAllocatedMb", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f MB", it) }
            ?: "N/A"
        val javaHeapMb = telemetry?.optDouble("javaHeapUsedMb", Double.NaN)
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f MB", it) }
            ?: "N/A"

        coreStatusView.text = "3D Volume: $coreState · $coreDims · active=$coreEnabled · texId=$coreTexId"

        gpuInfoView.text = buildString {
            appendLine("Page State       : ${pageState.name}")
            appendLine("Active Mode      : ${currentMode.name}")
            appendLine("Core 3D Volume   : $coreState (enabled=$coreEnabled · $coreDims)")
            appendLine("Core GL Binding  : texId=$coreTexId · unit=1 · uVolLoc=$coreSamplerLoc · uRotLoc=$coreRotLoc")
            appendLine("Core Upload/VRAM : $coreUploadMs / $coreVramMb · det(R)=$coreDet")
            appendLine("GL Vendor        : $vendor")
            appendLine("GL Renderer      : $renderer")
            appendLine("GLES Version     : $glesVer")
            appendLine("Shader Compile   : $shaderStatus")
            appendLine("Actual Draw Count: $drawCount")
            appendLine("Actual Pass Count: $passCount (Optics: $opticalCount)")
            appendLine("GPU Frame Time   : $gpuFrameTime")
            appendLine("CPU Frame Time   : $cpuFrameTime")
            appendLine("Staging / 1stFrm : $stageMs / $firstFrameMs")
            appendLine("Est. GPU VRAM    : $totalVramMb")
            appendLine("Heap (Nat / Java): $nativeHeapMb / $javaHeapMb")
            appendLine("Present Viewport : $vpStr")
            appendLine("GPU Class        : $gpuClass")
            append("Device           : $deviceInfo")
        }
    }

    private fun createSectionCard(tagId: String, title: String): LinearLayout {
        val card = LinearLayout(context).apply {
            tag = tagId
            orientation = LinearLayout.VERTICAL
            background = pillBackground(Color.parseColor("#B3061F2A"), Color.parseColor("#4D43CBE0"), dp(14))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val header = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#FF7AEBFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        card.addView(header)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        return card
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private fun pillBackground(fillColor: Int, strokeColor: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }
}
