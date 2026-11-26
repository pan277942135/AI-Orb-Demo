package com.example.waterworld

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads the canonical Work 1.6 scene bindings, material definitions, lighting rig,
 * and 5-effect optical configuration consumed by the 11-stage Render Graph.
 */
internal object WaterWorldSceneBindings {

    data class TextureRef(
        val path: String,
        val sha256: String
    )

    data class GodRayBeam(
        val angleDeg: Float,
        val widthDeg: Float,
        val strength: Float
    )

    data class BokehOrb(
        val x: Float,
        val y: Float,
        val radius: Float,
        val color: FloatArray,
        val alpha: Float,
        val softness: Float,
        val layer: String
    )

    data class OpticalConfig(
        val sphereCenterPx: FloatArray,
        val sphereOuterRadiusPx: Float,
        val sphereInnerRadiusPx: Float,
        val waterNormalTex: TextureRef,
        val waterDepthTex: TextureRef,
        val causticsHeightTex: TextureRef,
        val glassShellMaskTex: TextureRef,
        val topGlareSuppression: Float,
        val vignetteStrength: Float,
        val bgDeepTint: FloatArray,
        val bgHaloCenter: FloatArray,
        val bgHaloRadius: Float,
        val bgHaloColor: FloatArray,
        val bgHaloIntensity: Float,
        val backRefractionStrength: Float,
        val frontRefractionStrength: Float,
        val chromaticDispersion: Float,
        val fresnelPower: Float,
        val rimColorCool: FloatArray,
        val rimColorWarm: FloatArray,
        val glassAbsorptionColor: FloatArray,
        val topLeftFlareNorm: FloatArray,
        val topLeftFlareRadius: Float,
        val topLeftFlareColor: FloatArray,
        val topLeftFlareIntensity: Float,
        val bottomRightFlareNorm: FloatArray,
        val bottomRightFlareRadius: Float,
        val bottomRightFlareColor: FloatArray,
        val bottomRightFlareIntensity: Float,
        val rightRimArcNorm: FloatArray,
        val rightRimArcIntensity: Float,
        val waterNormalStrength: Float,
        val meniscusBaseYNorm: Float,
        val meniscusWaveAmpNorm: Float,
        val meniscusThicknessNorm: Float,
        val airDomeTopColor: FloatArray,
        val airDomeWarmColor: FloatArray,
        val waterShallowColor: FloatArray,
        val waterMidColor: FloatArray,
        val waterDeepColor: FloatArray,
        val waterbedBlend: Float,
        val sunOriginNorm: FloatArray,
        val godRayIntensity: Float,
        val godRayColor: FloatArray,
        val godRayBeams: List<GodRayBeam>,
        val surfaceCausticStrength: Float,
        val bedCausticStrength: Float,
        val bedCausticColor: FloatArray,
        val bloomThreshold: Float,
        val bloomSoftKnee: Float,
        val bloomBlurRadiusPx: Int,
        val bloomStrength: Float,
        val bloomMaxContribution: Float,
        val bokehOrbs: List<BokehOrb>
    )

    data class LoadedScene(
        val canvasSize: Int,
        val bindings: List<WaterWorldStaticRenderer.Binding>,
        val opticalConfig: OpticalConfig
    )

    private fun floats(array: JSONArray, count: Int): FloatArray {
        require(array.length() == count) { "Expected $count floats, got ${array.length()}" }
        return FloatArray(count) {
            array.getDouble(it).toFloat().also { value ->
                require(value.isFinite()) { "Non-finite float at index $it" }
            }
        }
    }

    private fun texRef(obj: JSONObject): TextureRef {
        val path = obj.getString("path")
        val sha = obj.getString("sha256")
        require(!path.lowercase().contains("master") && !path.contains("奇幻")) {
            "Forbidden master texture reference: $path"
        }
        require(sha.length == 64) { "Invalid SHA-256 length for $path" }
        return TextureRef(path = path, sha256 = sha)
    }

    fun load(root: File): List<WaterWorldStaticRenderer.Binding> = loadScene(root).bindings

    fun loadScene(root: File): LoadedScene {
        val source = JSONObject(File(root, "03_SCENE_GRAPH/draw_bindings.json").readText())
        val canvasSize = source.getInt("canvas_size")
        require(canvasSize == 1254 && source.getDouble("time_seconds") == 0.0) {
            "Unexpected canvas_size=$canvasSize or time_seconds!=0"
        }
        val materials = JSONObject(File(root, "02_SUPPLEMENTED_ASSETS/materials/materials.json").readText())
        val lighting = JSONObject(File(root, "02_SUPPLEMENTED_ASSETS/lighting/lighting.json").readText())
        val key = lighting.getJSONObject("key")
        val direction = floats(key.getJSONArray("direction"), 3)
        val light = floats(key.getJSONArray("color"), 3)
        val intensity = key.getDouble("intensity").toFloat()
        require(intensity.isFinite() && intensity >= 0f)
        for (i in light.indices) light[i] *= intensity

        val rows = source.getJSONArray("draws")
        val result = ArrayList<WaterWorldStaticRenderer.Binding>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val matName = row.getString("material")
            val material = materials.getJSONObject(matName)
            val baseTint = floats(material.getJSONArray("base_color"), 3) +
                material.getDouble("alpha").toFloat()
            val override = row.optJSONArray("tint")
            val actualTint = if (override == null) baseTint else floats(override, 4)
            val coreArr = row.optJSONArray("core_color")
            val coreColor = if (coreArr == null) floatArrayOf(1f, 0.95f, 0.85f) else floats(coreArr, 3)

            val passIdStr = row.optString("pass_id", inferPassId(row.getString("draw_id"), matName))
            val passId = WaterWorldRenderGraph.PassId.fromWireId(passIdStr)
            val shaderStr = row.optString("shader_program", inferShader(matName))
            val shaderProgram = WaterWorldRenderGraph.ShaderProgramId.valueOf(shaderStr)
            val blendStr = row.optString("blend_mode", if (matName == "background") "NONE" else "ALPHA")
            val blendMode = WaterWorldRenderGraph.BlendMode.valueOf(blendStr)

            result += WaterWorldStaticRenderer.Binding(
                objectId = row.getString("draw_id"),
                passId = passId,
                shaderProgram = shaderProgram,
                materialName = matName,
                meshPath = row.getString("mesh_path"),
                model = floats(row.getJSONArray("model"), 16),
                tint = actualTint,
                coreColor = coreColor,
                texturePath = if (row.isNull("texture_path")) null else row.getString("texture_path"),
                textureSha256 = if (row.isNull("texture_sha256")) null else row.getString("texture_sha256"),
                emission = row.optDouble("emission_override", material.getDouble("emission")).toFloat(),
                ambient = row.optDouble("ambient", 0.45).toFloat(),
                lightDirection = direction.copyOf(),
                lightColor = light.copyOf(),
                depthTest = row.optBoolean("depth_test", true),
                depthWrite = row.getBoolean("depth_write"),
                blendMode = blendMode,
                alphaCutoff = row.optDouble("alpha_cutoff", 0.0).toFloat(),
                fresnel = material.getDouble("fresnel").toFloat(),
                shellSide = row.optDouble("shell_side", 0.0).toFloat(),
                sphereClip = row.optBoolean("sphere_clip", false),
                ribbonStyle = row.optDouble("ribbon_style", 0.0).toFloat(),
                hypothesisStatus = row.optString("hypothesis_status", "GROUND_TRUTH_VERIFIED")
            )
        }
        require(result.isNotEmpty() && result.map { it.objectId }.distinct().size == result.size) {
            "Duplicate or empty draw_id list in draw_bindings.json"
        }

        val optJson = JSONObject(File(root, "02_SUPPLEMENTED_ASSETS/materials/optical_config.json").readText())
        val sphere = optJson.getJSONObject("sphere")
        val aux = optJson.getJSONObject("auxiliary_textures")
        val bg = optJson.getJSONObject("background_atmosphere")
        val glass = optJson.getJSONObject("glass_refraction")
        val water = optJson.getJSONObject("water_normal_distortion")
        val cau = optJson.getJSONObject("caustics")
        val bloom = optJson.getJSONObject("bloom")
        val bokeh = optJson.getJSONObject("bokeh")

        val beamsArr = cau.getJSONArray("god_ray_beams")
        val beams = List(beamsArr.length()) { idx ->
            val b = beamsArr.getJSONObject(idx)
            GodRayBeam(
                angleDeg = b.getDouble("angle_deg").toFloat(),
                widthDeg = b.getDouble("width_deg").toFloat(),
                strength = b.getDouble("strength").toFloat()
            )
        }

        val orbsArr = bokeh.getJSONArray("orbs")
        val orbs = List(orbsArr.length()) { idx ->
            val o = orbsArr.getJSONObject(idx)
            BokehOrb(
                x = o.getDouble("x").toFloat(),
                y = o.getDouble("y").toFloat(),
                radius = o.getDouble("r").toFloat(),
                color = floats(o.getJSONArray("color"), 3),
                alpha = o.getDouble("alpha").toFloat(),
                softness = o.getDouble("softness").toFloat(),
                layer = o.getString("layer")
            )
        }

        val opticalConfig = OpticalConfig(
            sphereCenterPx = floats(sphere.getJSONArray("center_px"), 2),
            sphereOuterRadiusPx = sphere.getDouble("outer_radius_px").toFloat(),
            sphereInnerRadiusPx = sphere.getDouble("inner_radius_px").toFloat(),
            waterNormalTex = texRef(aux.getJSONObject("water_normal")),
            waterDepthTex = texRef(aux.getJSONObject("water_depth")),
            causticsHeightTex = texRef(aux.getJSONObject("caustics_height")),
            glassShellMaskTex = texRef(aux.getJSONObject("glass_shell_mask")),
            topGlareSuppression = bg.getDouble("top_glare_suppression").toFloat(),
            vignetteStrength = bg.getDouble("vignette_strength").toFloat(),
            bgDeepTint = floats(bg.getJSONArray("deep_tint"), 3),
            bgHaloCenter = floats(bg.getJSONArray("top_center_halo_center"), 2),
            bgHaloRadius = bg.getDouble("top_center_halo_radius").toFloat(),
            bgHaloColor = floats(bg.getJSONArray("top_center_halo_color"), 3),
            bgHaloIntensity = bg.getDouble("top_center_halo_intensity").toFloat(),
            backRefractionStrength = glass.getDouble("back_refraction_strength").toFloat(),
            frontRefractionStrength = glass.getDouble("front_refraction_strength").toFloat(),
            chromaticDispersion = glass.getDouble("chromatic_dispersion").toFloat(),
            fresnelPower = glass.getDouble("fresnel_power").toFloat(),
            rimColorCool = floats(glass.getJSONArray("rim_color_cool"), 3),
            rimColorWarm = floats(glass.getJSONArray("rim_color_warm"), 3),
            glassAbsorptionColor = floats(glass.getJSONArray("absorption_color"), 3),
            topLeftFlareNorm = floats(glass.getJSONArray("top_left_flare_norm"), 2),
            topLeftFlareRadius = glass.getDouble("top_left_flare_radius").toFloat(),
            topLeftFlareColor = floats(glass.getJSONArray("top_left_flare_color"), 3),
            topLeftFlareIntensity = glass.getDouble("top_left_flare_intensity").toFloat(),
            bottomRightFlareNorm = floats(glass.getJSONArray("bottom_right_flare_norm"), 2),
            bottomRightFlareRadius = glass.getDouble("bottom_right_flare_radius").toFloat(),
            bottomRightFlareColor = floats(glass.getJSONArray("bottom_right_flare_color"), 3),
            bottomRightFlareIntensity = glass.getDouble("bottom_right_flare_intensity").toFloat(),
            rightRimArcNorm = floats(glass.getJSONArray("right_rim_arc_norm"), 2),
            rightRimArcIntensity = glass.getDouble("right_rim_arc_intensity").toFloat(),
            waterNormalStrength = water.getDouble("normal_distortion_strength").toFloat(),
            meniscusBaseYNorm = water.getDouble("meniscus_base_y_norm").toFloat(),
            meniscusWaveAmpNorm = water.getDouble("meniscus_wave_amp_norm").toFloat(),
            meniscusThicknessNorm = water.getDouble("meniscus_thickness_norm").toFloat(),
            airDomeTopColor = floats(water.getJSONArray("air_dome_top_color"), 3),
            airDomeWarmColor = floats(water.getJSONArray("air_dome_warm_color"), 3),
            waterShallowColor = floats(water.getJSONArray("water_shallow_color"), 3),
            waterMidColor = floats(water.getJSONArray("water_mid_color"), 3),
            waterDeepColor = floats(water.getJSONArray("water_deep_color"), 3),
            waterbedBlend = water.getDouble("waterbed_blend").toFloat(),
            sunOriginNorm = floats(cau.getJSONArray("sun_origin_norm"), 2),
            godRayIntensity = cau.getDouble("god_ray_intensity").toFloat(),
            godRayColor = floats(cau.getJSONArray("god_ray_color"), 3),
            godRayBeams = beams,
            surfaceCausticStrength = cau.getDouble("surface_caustic_strength").toFloat(),
            bedCausticStrength = cau.getDouble("bed_caustic_strength").toFloat(),
            bedCausticColor = floats(cau.getJSONArray("bed_caustic_color"), 3),
            bloomThreshold = bloom.getDouble("threshold").toFloat(),
            bloomSoftKnee = bloom.getDouble("soft_knee").toFloat(),
            bloomBlurRadiusPx = bloom.getInt("blur_radius_px"),
            bloomStrength = bloom.getDouble("strength").toFloat(),
            bloomMaxContribution = bloom.getDouble("max_contribution").toFloat(),
            bokehOrbs = orbs
        )

        return LoadedScene(canvasSize = canvasSize, bindings = result, opticalConfig = opticalConfig)
    }

    private fun inferPassId(drawId: String, material: String): String = when (material) {
        "background" -> "01_BACKGROUND"
        "resident" -> if (drawId.contains("front", ignoreCase = true)) "10_RESIDENT_FRONT" else "02_RESIDENT_BACK"
        "glass" -> if (drawId.contains("front", ignoreCase = true)) "09_SPHERE_FRONT" else "03_SPHERE_BACK"
        "water" -> "04_WATER_INTERIOR"
        "fish" -> "07_FISH"
        "plant", "rock" -> if (drawId.startsWith("far_")) "05_FAR_ECOLOGY" else "08_NEAR_ECOLOGY"
        else -> "11_FINAL_COMPOSITE"
    }

    private fun inferShader(material: String): String = when (material) {
        "background" -> "OPAQUE"
        "resident" -> "RESIDENT_RIBBON"
        "glass" -> "GLASS"
        "water" -> "WATER"
        "bubble" -> "PARTICLE_BOKEH"
        else -> "TRANSPARENT"
    }
}
