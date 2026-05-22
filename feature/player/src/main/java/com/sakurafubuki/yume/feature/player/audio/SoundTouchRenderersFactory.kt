package com.sakurafubuki.yume.feature.player.audio

import android.content.Context
import android.media.AudioTrack
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import com.sakurafubuki.yume.core.model.AudioOutputMode
import com.sakurafubuki.yume.feature.player.ass.AssSubtitleDecoderFactory
import io.github.sakurafubuki.yume.nativelib.player.YumeRenderersFactory
import io.github.sakurafubuki.yume.nativelib.renderer.YumeTextRenderer

@OptIn(UnstableApi::class)
class SoundTouchRenderersFactory(
    context: Context,
    private val audioOutputMode: AudioOutputMode = AudioOutputMode.AUDIO_TRACK,
) : YumeRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(YumeTextRenderer(output, outputLooper, AssSubtitleDecoderFactory()))
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val audioOutputProvider = AudioTrackAudioOutputProvider.Builder(context)
            .setAudioTrackBuilderModifier { trackBuilder, _ ->
                val performanceMode = when (audioOutputMode) {
                    AudioOutputMode.AAUDIO_LOW_LATENCY -> AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    AudioOutputMode.AAUDIO_POWER_SAVING -> AudioTrack.PERFORMANCE_MODE_POWER_SAVING
                    AudioOutputMode.AUDIO_TRACK -> AudioTrack.PERFORMANCE_MODE_NONE
                }
                trackBuilder.setPerformanceMode(performanceMode)
            }
            .setAudioOffloadSupportProvider { format, _ ->
                if (audioOutputMode == AudioOutputMode.AAUDIO_LOW_LATENCY) {
                    AudioOffloadSupport.DEFAULT_UNSUPPORTED
                } else {
                    AudioOffloadSupport.Builder()
                        .setIsFormatSupported(true)
                        .setIsGaplessSupported(false)
                        .setIsSpeedChangeSupported(false)
                        .build()
                }
            }
            .build()

        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioProcessorChain(SoundTouchAudioProcessorChain())
            .setAudioOutputProvider(audioOutputProvider)
            .build()
    }
}
