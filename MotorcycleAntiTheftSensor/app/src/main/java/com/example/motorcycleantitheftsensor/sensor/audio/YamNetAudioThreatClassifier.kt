package com.example.motorcycleantitheftsensor.sensor.audio

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions

class YamNetAudioThreatClassifier(
    context: Context,
    modelAssetPath: String = "yamnet.tflite",
) : AudioThreatClassifier, AutoCloseable {

    private val classifier: AudioClassifier
    private val audioFormat = AudioDataFormat.builder()
        .setNumOfChannels(1)
        .setSampleRate(16000f)
        .build()

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .build()
        val options = AudioClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .build()
        classifier = AudioClassifier.createFromOptions(context, options)
    }

    @Synchronized
    override fun classify(samples: FloatArray): List<AudioLabelScore> {
        val audioData = AudioData.create(audioFormat, samples.size)
        audioData.load(samples, 0, samples.size)
        val result = classifier.classify(audioData)
        val classificationResults = result.classificationResults()
        if (classificationResults.isEmpty()) return emptyList()

        val headResult = classificationResults[0]
        val classifications = headResult.classifications()
        if (classifications.isEmpty()) return emptyList()

        val categories = classifications[0].categories()
        return categories.map { AudioLabelScore(it.categoryName(), it.score().toDouble()) }
    }

    @Synchronized
    override fun close() {
        classifier.close()
    }
}
