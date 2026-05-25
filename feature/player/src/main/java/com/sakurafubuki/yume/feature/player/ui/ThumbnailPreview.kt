package com.sakurafubuki.yume.feature.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sakurafubuki.yume.core.data.repository.SpriteSheetMetadata
import kotlin.math.roundToInt

@Composable
fun ThumbnailPreview(
    spriteSheet: Bitmap,
    metadata: SpriteSheetMetadata,
    frameIndex: Int,
    seekFraction: Float,
    parentWidth: Float,
    modifier: Modifier = Modifier,
    chapterTitle: String? = null,
) {
    val density = LocalDensity.current

    val maxPreviewDp = 160
    val previewWidthDp: Float
    val previewHeightDp: Float
    if (metadata.thumbWidth >= metadata.thumbHeight) {
        previewWidthDp = maxPreviewDp.toFloat()
        previewHeightDp = maxPreviewDp * metadata.thumbHeight.toFloat() / metadata.thumbWidth
    } else {
        previewWidthDp = maxPreviewDp * metadata.thumbWidth.toFloat() / metadata.thumbHeight
        previewHeightDp = maxPreviewDp.toFloat()
    }

    val previewWidthPx = with(density) { previewWidthDp.dp.toPx() }
    val previewHeightPx = with(density) { previewHeightDp.dp.toPx() }

    val titleHeightDp = if (chapterTitle != null) 22.dp else 0.dp
    val titleHeightPx = with(density) { titleHeightDp.toPx() }
    val titleGapPx = with(density) { if (chapterTitle != null) 4.dp.toPx() else 0f }

    val rawOffsetX = (seekFraction * parentWidth) - (previewWidthPx / 2f)
    val clampedOffsetX = rawOffsetX.coerceIn(0f, parentWidth - previewWidthPx)

    val col = frameIndex % metadata.cols
    val row = frameIndex / metadata.cols
    val srcLeft = col * metadata.thumbWidth
    val srcTop = row * metadata.thumbHeight

    val imageBitmap = remember(spriteSheet) { spriteSheet.asImageBitmap() }

    Layout(
        modifier = modifier,
        content = {
            Canvas(
                modifier = Modifier
                    .width(previewWidthDp.dp)
                    .height(previewHeightDp.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray),
            ) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize(metadata.thumbWidth, metadata.thumbHeight),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(previewWidthPx.roundToInt(), previewHeightPx.roundToInt()),
                )
            }

            if (chapterTitle != null) {
                Box(
                    modifier = Modifier
                        .width(previewWidthDp.dp)
                        .height(titleHeightDp)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chapterTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val looseConstraints = Constraints(
            minWidth = 0,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val placeables = measurables.map { it.measure(looseConstraints) }

        layout(width = constraints.maxWidth, height = 0) {
            val thumbnailY = -previewHeightPx.roundToInt()
            placeables.getOrNull(0)?.placeRelative(
                x = clampedOffsetX.roundToInt(),
                y = thumbnailY,
            )
            if (placeables.size > 1) {
                val titleY = (-previewHeightPx - titleGapPx - titleHeightPx).roundToInt()
                placeables[1].placeRelative(
                    x = clampedOffsetX.roundToInt(),
                    y = titleY,
                )
            }
        }
    }
}
