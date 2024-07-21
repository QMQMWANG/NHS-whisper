package com.whispercppdemo.ui.main

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.Recorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.*
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set
    var status by mutableStateOf("Idle")
        private set
    var recordingTime by mutableStateOf(0L)
        private set
    var fhirRecord by mutableStateOf("")
    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")
    private var recorder: Recorder = Recorder()
    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null
    private var recordingJob: Job? = null

    private val _navigateToFhirScreen = MutableLiveData<String?>()
    val navigateToFhirScreen: LiveData<String?> get() = _navigateToFhirScreen

    // Other properties and methods...

    private fun navigateToFhirScreen(fhirResponse: String) {
        fhirRecord = fhirResponse
        _navigateToFhirScreen.value = fhirResponse
    }

    fun doneNavigating() {
        _navigateToFhirScreen.value = null
    }

    init {
        viewModelScope.launch {
            loadData()
        }
    }

    private suspend fun loadData() {
        try {
            copyAssets()
            loadBaseModel()
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        application.copyData("samples", samplesPath, ::printMessage)
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        val models = application.assets.list("models/")
        if (models != null) {
            whisperContext = com.whispercpp.whisper.WhisperContext.createContextFromAsset(application.assets, "models/" + models[0])
        }
    }

    fun transcribeSample() = viewModelScope.launch {
        transcribeAudio(getFirstSample())
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        samplesPath.listFiles()!!.first()
    }

    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        stopPlayback()
        startPlayback(file)
        return@withContext decodeWaveFile(file)
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    fun transcribeAudio(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!canTranscribe) {
                    return@launch
                }

                canTranscribe = false
                status = "Transcribing..."

                val data = readAudioSamples(file)
                val start = System.currentTimeMillis()
                val text = whisperContext?.transcribeData(data)
                val elapsed = System.currentTimeMillis() - start

                val cleanText = text?.replace(Regex("\\[.*?\\]"), "")
                    ?.replace(":", "")
                    ?.replace("\n", " ")
                    ?.replace("\\s+".toRegex(), " ")
                    ?.trim()

                withContext(Dispatchers.Main) {
                    printMessage("$cleanText\n")
                    status = "Transcription Successful"
                }

                cleanText?.let {
                    convertTextToFhir(it, { fhirResponse ->
                        viewModelScope.launch(Dispatchers.Main) {
                            navigateToFhirScreen(fhirResponse)
                        }
                    }, { error ->
                        viewModelScope.launch(Dispatchers.Main) {
                            printMessage("FHIR Conversion Failed: $error\n")
                        }
                    })
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, e)
                withContext(Dispatchers.Main) {
                    printMessage("${e.localizedMessage}\n")
                    status = "Transcription Failed"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    canTranscribe = true
                }
            }
        }
    }

    fun toggleRecord() = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                recordingJob?.cancel()
                recordedFile?.let { transcribeAudio(it) }
            } else {
                stopPlayback()
                val file = getTempFileForRecording()
                recorder.startRecording(file) { e ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            isRecording = false
                            status = "Idle"
                        }
                    }
                }
                isRecording = true
                status = "Recording..."
                recordingTime = 0L
                startRecordingTimer()
                recordedFile = file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
            status = "Idle"
        }
    }

    private fun startRecordingTimer() {
        val startTime = SystemClock.elapsedRealtime()
        recordingJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val elapsedTime = SystemClock.elapsedRealtime() - startTime
                withContext(Dispatchers.Main) {
                    recordingTime = elapsedTime
                }
                delay(1000L)
            }
        }
    }

    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        File.createTempFile("recording", "wav")
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
            stopPlayback()
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }

    private fun convertTextToFhir(text: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .callTimeout(200, TimeUnit.SECONDS)
                .connectTimeout(200, TimeUnit.SECONDS)
                .writeTimeout(200, TimeUnit.SECONDS)
                .readTimeout(200, TimeUnit.SECONDS)
                .build()
            val json = JSONObject()
            json.put("text", text)
            val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("http://10.0.2.2:5000/convert") // Use 10.0.2.2 to access localhost from Android emulator
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(LOG_TAG, "FHIR conversion successful: $responseBody")
                    withContext(Dispatchers.Main) {
                        onSuccess(responseBody ?: "Conversion failed: empty response")
                    }
                } else {
                    val errorMessage = "Conversion failed: ${response.message}"
                    Log.e(LOG_TAG, errorMessage)
                    withContext(Dispatchers.Main) {
                        onFailure(errorMessage)
                    }
                }
            } catch (e: IOException) {
                val errorMessage = "Conversion failed: ${e.localizedMessage}"
                Log.e(LOG_TAG, errorMessage)
                withContext(Dispatchers.Main) {
                    onFailure(errorMessage)
                }
            }
        }
    }}

    private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        val destination = File(destDir, name)
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
