package com.example.orb.ui

/**
 * Top-level Dual-Tab navigation enum for AI-Orb-Demo.
 * Both tabs share ONE live GLSurfaceView, ONE AIOrbScene, ONE V3 WebP 3D Volume,
 * and ONE AIOrbMotionController with zero GPU context rebuilds on tab switch.
 */
enum class DemoTab(val labelResId: Int) {
  AI_ORB(com.example.R.string.tab_ai_orb),
  TASK_PERIMETER(com.example.R.string.tab_task_perimeter),
}
