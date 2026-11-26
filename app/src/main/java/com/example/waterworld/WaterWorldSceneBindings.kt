package com.example.waterworld

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One explicit ordered binding list is consumed by rendering and evidence export. */
internal object WaterWorldSceneBindings {
    private fun floats(array: JSONArray, count: Int): FloatArray {
        require(array.length() == count)
        return FloatArray(count) { array.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
    }
    fun load(root: File): List<WaterWorldStaticRenderer.Binding> {
        val source = JSONObject(File(root,"03_SCENE_GRAPH/draw_bindings.json").readText())
        require(source.getInt("canvas_size") == 1254 && source.getDouble("time_seconds") == 0.0)
        val materials = JSONObject(File(root,"02_SUPPLEMENTED_ASSETS/materials/materials.json").readText())
        val lighting = JSONObject(File(root,"02_SUPPLEMENTED_ASSETS/lighting/lighting.json").readText())
        val key = lighting.getJSONObject("key")
        val direction = floats(key.getJSONArray("direction"),3)
        val light = floats(key.getJSONArray("color"),3)
        val intensity=key.getDouble("intensity").toFloat()
        require(intensity.isFinite() && intensity >= 0)
        for(i in light.indices) light[i] *= intensity
        val rows=source.getJSONArray("draws")
        val result=ArrayList<WaterWorldStaticRenderer.Binding>()
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            val material=materials.getJSONObject(row.getString("material"))
            val tint=floats(material.getJSONArray("base_color"),3)+material.getDouble("alpha").toFloat()
            val override=row.optJSONArray("tint")
            val actualTint=if(override==null) tint else floats(override,4)
            result += WaterWorldStaticRenderer.Binding(
                objectId=row.getString("draw_id"),meshPath=row.getString("mesh_path"),
                model=floats(row.getJSONArray("model"),16),tint=actualTint,
                texturePath=if(row.isNull("texture_path")) null else row.getString("texture_path"),
                textureSha256=if(row.isNull("texture_sha256")) null else row.getString("texture_sha256"),
                emission=material.getDouble("emission").toFloat(),
                ambient=row.optDouble("ambient",.35).toFloat(),lightDirection=direction.copyOf(),lightColor=light.copyOf(),
                depthWrite=row.getBoolean("depth_write"),fresnel=material.getDouble("fresnel").toFloat())
        }
        require(result.isNotEmpty() && result.map { it.objectId }.distinct().size==result.size)
        return result
    }
}
