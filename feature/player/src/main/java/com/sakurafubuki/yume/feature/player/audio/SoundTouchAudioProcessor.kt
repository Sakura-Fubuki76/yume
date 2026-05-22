package com.sakurafubuki.yume.feature.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import io.github.sakurafubuki.yume.nativelib.soundtouch.SoundTouch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@OptIn(UnstableApi::class)
class SoundTouchAudioProcessor : AudioProcessor {
    private var speed = 1f
    private var pitch = 1f
    private var pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var soundTouch: SoundTouch? = null
    private var inputSamples = ShortArray(0)
    private var outputSamples = ShortArray(0)
    private var inputBytes = 0L
    private var outputBytes = 0L
    private var inputEnded = false
    private var pendingSoundTouchRecreation = true

    fun setSpeed(speed: Float) {
        require(speed > 0f)
        if (this.speed != speed) {
            this.speed = speed
            pendingSoundTouchRecreation = true
        }
    }

    fun setPitch(pitch: Float) {
        require(pitch > 0f)
        if (this.pitch != pitch) {
            this.pitch = pitch
            pendingSoundTouchRecreation = true
        }
    }

    fun getMediaDuration(playoutDuration: Long): Long = if (outputBytes >= MIN_BYTES_FOR_DURATION_SCALING_CALCULATION && inputBytes > 0L) {
        (playoutDuration.toDouble() * inputBytes.toDouble() / outputBytes.toDouble()).toLong()
    } else {
        (playoutDuration.toDouble() * speed).toLong()
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat = AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT,
        )
        pendingSoundTouchRecreation = true
        return if (isActive) pendingOutputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = pendingOutputAudioFormat != AudioProcessor.AudioFormat.NOT_SET &&
        (abs(speed - 1f) > CLOSE_THRESHOLD || abs(pitch - 1f) > CLOSE_THRESHOLD)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || outputBuffer.hasRemaining()) return
        if (!isActive) {
            val passthrough = ByteBuffer.allocateDirect(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
            passthrough.put(inputBuffer)
            passthrough.flip()
            outputBuffer = passthrough
            return
        }

        val processor = ensureSoundTouch()
        val byteCount = inputBuffer.remaining()
        val frameCount = byteCount / inputAudioFormat.bytesPerFrame
        val sampleCount = frameCount * inputAudioFormat.channelCount
        if (inputSamples.size < sampleCount) {
            inputSamples = ShortArray(sampleCount)
        }
        inputBuffer.asShortBuffer().get(inputSamples, 0, sampleCount)
        inputBuffer.position(inputBuffer.position() + sampleCount * BYTES_PER_SHORT)
        processor.putSamples(inputSamples, 0, frameCount)
        inputBytes += frameCount.toLong() * inputAudioFormat.bytesPerFrame
        drainOutput()
    }

    override fun queueEndOfStream() {
        inputEnded = true
        if (isActive) {
            ensureSoundTouch().flush()
            drainOutput()
        }
    }

    override fun getOutput(): ByteBuffer {
        if (!outputBuffer.hasRemaining() && inputEnded) {
            drainOutput()
        }
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean {
        if (inputEnded && !outputBuffer.hasRemaining()) {
            drainOutput()
        }
        return inputEnded && !outputBuffer.hasRemaining() && (soundTouch?.numSamples() ?: 0L) == 0L
    }

    @Deprecated("Use flush(StreamMetadata) instead")
    override fun flush() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputAudioFormat = pendingInputAudioFormat
        outputAudioFormat = pendingOutputAudioFormat
        inputBytes = 0L
        outputBytes = 0L
        if (isActive) {
            recreateSoundTouch()
        } else {
            releaseSoundTouch()
        }
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputBytes = 0L
        outputBytes = 0L
        releaseSoundTouch()
    }

    private fun ensureSoundTouch(): SoundTouch {
        if (soundTouch == null || pendingSoundTouchRecreation) {
            recreateSoundTouch()
        }
        return requireNotNull(soundTouch)
    }

    private fun recreateSoundTouch() {
        releaseSoundTouch()
        soundTouch = SoundTouch().apply {
            setSampleRate(outputAudioFormat.sampleRate.toLong())
            setChannels(outputAudioFormat.channelCount.toLong())
            setTempo(speed)
            setPitch(pitch)
        }
        pendingSoundTouchRecreation = false
    }

    private fun releaseSoundTouch() {
        soundTouch?.dispose()
        soundTouch = null
    }

    private fun drainOutput() {
        if (!isActive || outputBuffer.hasRemaining()) return
        val processor = soundTouch ?: return
        val availableFrames = processor.numSamples().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (availableFrames <= 0) return
        val sampleCount = availableFrames * outputAudioFormat.channelCount
        if (outputSamples.size < sampleCount) {
            outputSamples = ShortArray(sampleCount)
        }
        val receivedFrames = processor.receiveSamplesI16(outputSamples, 0, availableFrames)
        if (receivedFrames <= 0) return
        val outputSize = receivedFrames * outputAudioFormat.bytesPerFrame
        val buffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(outputSamples, 0, receivedFrames * outputAudioFormat.channelCount)
        buffer.limit(outputSize)
        outputBuffer = buffer
        outputBytes += outputSize.toLong()
    }

    private companion object {
        private const val BYTES_PER_SHORT = 2
        private const val CLOSE_THRESHOLD = 0.0001f
        private const val MIN_BYTES_FOR_DURATION_SCALING_CALCULATION = 1024
    }
}

@OptIn(UnstableApi::class)
class SoundTouchAudioProcessorChain(
    private val additionalAudioProcessors: Array<AudioProcessor> = emptyArray(),
) : AudioProcessorChain {
    private val silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor()
    private val soundTouchAudioProcessor = SoundTouchAudioProcessor()
    private val audioProcessors = additionalAudioProcessors +
        arrayOf<AudioProcessor>(silenceSkippingAudioProcessor, soundTouchAudioProcessor)

    override fun getAudioProcessors(): Array<AudioProcessor> = audioProcessors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        soundTouchAudioProcessor.setSpeed(playbackParameters.speed)
        soundTouchAudioProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long = if (soundTouchAudioProcessor.isActive) {
        soundTouchAudioProcessor.getMediaDuration(playoutDuration)
    } else {
        playoutDuration
    }

    override fun getSkippedOutputFrameCount(): Long = silenceSkippingAudioProcessor.skippedFrames
}
