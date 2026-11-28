package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.orb.ui.DemoTab

@Composable
fun DemoTabSwitcherBar(
  activeTab: DemoTab,
  onSelectTab: (DemoTab) -> Unit,
  isLightTheme: Boolean,
  modifier: Modifier = Modifier,
  showWaterWorldTab: Boolean = BuildConfig.DEBUG,
  onOpenWaterWorld: () -> Unit = {},
) {
  val containerColor = if (isLightTheme) {
    ComposeColor(0xD8F1FCFF)
  } else {
    ComposeColor(0xA6061A20)
  }
  val borderColor = if (isLightTheme) {
    ComposeColor(0x8A78CEE0)
  } else {
    ComposeColor(0x6636B8C9)
  }
  val activePillColor = if (isLightTheme) {
    ComposeColor(0xFF0A8FA8)
  } else {
    ComposeColor(0xCC1293A8)
  }
  val activeTextColor = ComposeColor.White
  val inactiveTextColor = if (isLightTheme) {
    ComposeColor(0xFF0F5062)
  } else {
    ComposeColor(0xCCBEEEF5)
  }
  val segmentHorizontalPadding = if (showWaterWorldTab) 12.dp else 16.dp

  Surface(
    shape = RoundedCornerShape(26.dp),
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    shadowElevation = if (isLightTheme) 2.dp else 0.dp,
    modifier = modifier.testTag("demo_tab_switcher"),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(4.dp),
    ) {
      DemoTabSegment(
        label = stringResource(R.string.tab_ai_orb),
        selected = activeTab == DemoTab.AI_ORB,
        activeBg = activePillColor,
        activeText = activeTextColor,
        inactiveText = inactiveTextColor,
        horizontalPadding = segmentHorizontalPadding,
        tag = "tab_ai_orb",
        onClick = { onSelectTab(DemoTab.AI_ORB) },
      )
      DemoTabSegment(
        label = stringResource(R.string.tab_task_perimeter),
        selected = activeTab == DemoTab.TASK_PERIMETER,
        activeBg = activePillColor,
        activeText = activeTextColor,
        inactiveText = inactiveTextColor,
        horizontalPadding = segmentHorizontalPadding,
        tag = "tab_task_perimeter",
        onClick = { onSelectTab(DemoTab.TASK_PERIMETER) },
      )
      if (showWaterWorldTab) {
        DemoTabSegment(
          label = stringResource(R.string.tab_water_world),
          selected = false,
          activeBg = activePillColor,
          activeText = activeTextColor,
          inactiveText = inactiveTextColor,
          horizontalPadding = segmentHorizontalPadding,
          tag = "tab_water_world",
          onClick = onOpenWaterWorld,
        )
      }
    }
  }
}

@Composable
private fun DemoTabSegment(
  label: String,
  selected: Boolean,
  activeBg: ComposeColor,
  activeText: ComposeColor,
  inactiveText: ComposeColor,
  horizontalPadding: Dp,
  tag: String,
  onClick: () -> Unit,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(22.dp))
      .background(if (selected) activeBg else ComposeColor.Transparent)
      .clickable(role = Role.Tab, onClick = onClick)
      .padding(horizontal = horizontalPadding, vertical = 10.dp)
      .testTag(tag),
  ) {
    Text(
      text = label,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.labelLarge.copy(
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      ),
      color = if (selected) activeText else inactiveText,
    )
  }
}
