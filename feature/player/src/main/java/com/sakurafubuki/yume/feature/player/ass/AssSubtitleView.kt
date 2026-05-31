package com.sakurafubuki.yume.feature.player.ass

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
fun AssSubtitleView(
    modifier: Modifier = Modifier,
    state: AssSubtitleState,
) {
    val embeddedActive by produceState(false) {
        while (true) {
            val active = AssSubtitleState.embeddedTrackActive
            if (value != active) value = active
            delay(200L)
        }
    }
    if (!state.isLoaded && !embeddedActive) return

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setZOrderMediaOverlay(true)

                holder.addCallback(object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        state.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        state.setSurface(holder.surface)
                        state.setFrameSize(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        state.setSurface(null)
                    }
                })
            }
        },
    )
}
