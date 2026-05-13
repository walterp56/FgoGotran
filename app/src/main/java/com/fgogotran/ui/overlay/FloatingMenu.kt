package com.fgogotran.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A plain white popup menu with rounded corners, designed to be shown
 * in an overlay AlertDialog when the user taps the floating button.
 *
 * Menu items:
 * - [onHistoryClick]: 查看翻譯歷史 → view translation history
 * - [onDismiss]: 關閉 → close the dialog
 */
@Composable
fun FloatingMenu(
    onHistoryClick: () -> Unit,
    onDismiss: () -> Unit
) {
    // White card with rounded corners
    Column(
        modifier = Modifier
            .width(220.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp)
    ) {
        // Title
        Text(
            text = "FgoGotran",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF333333),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        // Menu item 1: Translation History
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onHistoryClick() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "📋",  // 📋 clipboard emoji as icon
                fontSize = 16.sp
            )
            Text(
                text = "查看翻譯歷史",
                fontSize = 15.sp,
                color = Color(0xFF333333)
            )
        }

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        // Menu item 2: Close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "✕",  // ✕ close icon
                fontSize = 16.sp,
                color = Color(0xFF999999)
            )
            Text(
                text = "關閉",
                fontSize = 15.sp,
                color = Color(0xFF999999)
            )
        }
    }
}
