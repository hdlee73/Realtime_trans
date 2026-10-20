package com.example.nanotranslator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// EngHub 와 같은 민트-틸 팔레트
val Teal = Color(0xFF168C82)
val Mint = Color(0xFF1E9A8C)
val Ink = Color(0xFF172B2A)
val Canvas = Color(0xFFEAF3F1)
val Muted = Color(0xFF6B7D7A)
val SoftTeal = Color(0xFFDDF1EC)
val Line = Color(0xFFDCE9E6)
val Alert = Color(0xFFE5675A)
private val HeroStart = Color(0xFF22A394)
private val HeroEnd = Color(0xFF0C6F67)

@Composable
fun NanoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, primaryContainer = SoftTeal, onPrimaryContainer = HeroEnd,
            secondary = Mint, secondaryContainer = SoftTeal, onSecondaryContainer = HeroEnd,
            tertiary = HeroEnd, background = Canvas, surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFF1F6F5), outline = Color(0xFFB9CFCA), outlineVariant = Line,
            error = Alert
        ),
        content = content
    )
}

/** 화면 맨 위의 그라데이션 배너 */
@Composable
fun Hero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(HeroStart, HeroEnd)))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 24.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        trailing?.invoke(this)
    }
}

/** 흰색 둥근 카드 */
@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(16.dp)
    ) { content() }
}

/** 알약 모양 선택 칩 */
@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Teal else Color.White)
            .border(1.dp, if (selected) Teal else Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else Ink,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 상태 표시용 작은 알약 */
@Composable
fun StatusPill(text: String, active: Boolean = false) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(SoftTeal)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (active) Box(Modifier.size(8.dp).clip(CircleShape).background(Alert))
        Text(text, color = HeroEnd, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** 큰 시작/중지 버튼 */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Alert else Mint,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFD3E2DF),
            disabledContentColor = Color(0xFF86A09B)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) }
}

/** 배너 오른쪽 흰색 텍스트 버튼 */
@Composable
fun HeroAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}
