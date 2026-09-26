package com.example.navis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

import com.google.android.gms.location.LocationServices
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient

import java.util.Locale

import com.example.navis.BuildConfig
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import android.speech.tts.UtteranceProgressListener
import android.content.SharedPreferences
import androidx.camera.view.PreviewView
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.dp
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.os.BatteryManager
import android.content.IntentFilter
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority







class MainActivity : ComponentActivity() {
    private var lowBatteryWarningGiven = false

    private val batteryReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            val level = intent?.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
            ) ?: return

            val scale = intent?.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                -1
            ) ?: return

            if (level < 0 || scale <= 0) {
                return
            }

            val batteryPercentage =
                (level * 100) / scale

            if (batteryPercentage <= 20 &&
                !lowBatteryWarningGiven
            ) {

                lowBatteryWarningGiven = true

                speak(
                    "Warning. Your battery is low. " +
                            "Please charge your phone soon."
                )

                updateStatus?.invoke(
                    "Low battery: $batteryPercentage percent"
                )
            }

            if (batteryPercentage > 20) {
                lowBatteryWarningGiven = false
            }
        }
    }
    private var obstacleWarningActive = false
    private val obstacleResumeHandler = Handler(Looper.getMainLooper())
    private var obstacleResumePending = false
    private var obstacleFrameCount = 0
    private var lastObstacleWarningTime = 0L

    private val obstacleConfirmationFrames = 5
    private val obstacleWarningCooldown = 5000L
    private var lastDetectedObject = ""
    private var lastDetectionTime = 0L
    private val objectDetector by lazy {

        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(
                ObjectDetectorOptions.STREAM_MODE
            )
            .enableMultipleObjects()
            .enableClassification()
            .build()

        ObjectDetection.getClient(options)
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var frameCount = 0
    private var firstCameraFrameReceived = false

    private fun selectLanguageFromScreen(
        language: String,
        languageCode: String
    ) {
        selectedLanguage = language
        selectedLanguageCode = languageCode

        preferences.edit()
            .putString("selectedLanguage", language)
            .putString("selectedLanguageCode", languageCode)
            .apply()

        val locale = Locale.forLanguageTag(languageCode)

        val result = textToSpeech.setLanguage(locale)

        val matchingVoice = textToSpeech.voices
            .firstOrNull { voice ->
                voice.locale == locale &&
                        !voice.isNetworkConnectionRequired
            }

        if (matchingVoice != null) {
            textToSpeech.voice = matchingVoice
        }

        updateStatus?.invoke(
            "Language selected: $language"
        )

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            textToSpeech.language = Locale.UK

            speak(
                "$language selected, " +
                        "but this language is not available for speech on this device."
            )
        } else {
            speak(
                "$language selected. NavIs is ready."
            )
        }
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera(
        previewView: PreviewView
    ) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            cameraPreviewView = previewView

            if (!cameraPermissionRequested) {
                cameraPermissionRequested = true

                cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }

            return
        }

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            // Camera preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider =
                        previewView.surfaceProvider
                }

            // Live camera frame analysis
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->

                val mediaImage = imageProxy.image

                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )
                Log.d(
                    "NAVIS_CAMERA",
                    "Camera analyzer is receiving frames"
                )

                objectDetector.process(image)
                    .addOnSuccessListener { detectedObjects ->

                        Log.d(
                            "NAVIS_OBJECT",
                            "SUCCESS: ML Kit returned ${detectedObjects.size} objects"
                        )

                        var obstacleDetectedInFrame = false
                        for ((index, detectedObject) in detectedObjects.withIndex()) {

                            Log.d(
                                "NAVIS_OBJECT",
                                "Object $index - trackingId=${detectedObject.trackingId}"
                            )

                            Log.d(
                                "NAVIS_OBJECT",
                                "Object $index - labels=${detectedObject.labels.size}"
                            )

                            if (obstacleDetectedInFrame) {
                                obstacleFrameCount++

                                Log.d(
                                    "NAVIS_OBSTACLE",
                                    "Potential obstacle frame: $obstacleFrameCount"
                                )

                                if (obstacleFrameCount >= obstacleConfirmationFrames) {
                                    val currentTime = System.currentTimeMillis()

                                    if (
                                        currentTime - lastObstacleWarningTime >=
                                        obstacleWarningCooldown
                                    ) {
                                        lastObstacleWarningTime = currentTime

                                        Log.d(
                                            "NAVIS_OBSTACLE",
                                            "OBSTACLE CONFIRMED"
                                        )
                                        announceObstacle("object")
                                    }

                                    obstacleFrameCount = 0
                                }
                            } else {
                                obstacleFrameCount = 0
                            }

                            for (label in detectedObject.labels) {
                                Log.d(
                                    "NAVIS_OBJECT",
                                    "RAW LABEL: ${label.text}, confidence=${label.confidence}"
                                )

                                // Ignore low-confidence classifications
                                if (label.confidence < 0.50f) {
                                    continue
                                }

                                Log.d(
                                    "NAVIS_OBJECT",
                                    "LABEL FOUND: ${label.text}, confidence=${label.confidence}"
                                )

                                // Get object's position and size
                                val box = detectedObject.boundingBox

                                val objectWidth = box.width()
                                val objectHeight = box.height()

                                val imageWidth = image.width
                                val imageHeight = image.height

                                val widthRatio =
                                    objectWidth.toFloat() / imageWidth.toFloat()

                                val heightRatio =
                                    objectHeight.toFloat() / imageHeight.toFloat()

                                Log.d(
                                    "NAVIS_OBJECT",
                                    "Object size: widthRatio=$widthRatio, " +
                                            "heightRatio=$heightRatio"
                                )
                                val centerX = box.centerX().toFloat()
                                val imageWidthFloat = image.width.toFloat()

                                val centerRegionStart = imageWidthFloat * 0.30f
                                val centerRegionEnd = imageWidthFloat * 0.70f

                                val isInWalkingDirection =
                                    centerX >= centerRegionStart &&
                                            centerX <= centerRegionEnd

                                Log.d(
                                    "NAVIS_OBSTACLE",
                                    "Label=${label.text}, " +
                                            "inWalkingDirection=$isInWalkingDirection, " +
                                            "centerX=$centerX"
                                )
                                val isLargeEnough =
                                    widthRatio >= 0.10f ||
                                            heightRatio >= 0.15f

                                val potentialObstacle =
                                    label.confidence >= 0.65f &&
                                            isInWalkingDirection &&
                                            isLargeEnough

                                Log.d(
                                    "NAVIS_OBSTACLE",
                                    "CHECK: label=${label.text}, " +
                                            "confidence=${label.confidence}, " +
                                            "walking=$isInWalkingDirection, " +
                                            "large=$isLargeEnough, " +
                                            "potential=$potentialObstacle"
                                )

                                if (potentialObstacle) {
                                    obstacleDetectedInFrame = true

                                    Log.d(
                                        "NAVIS_OBSTACLE",
                                        "Potential obstacle in current frame: ${label.text}"
                                    )
                                }

                            }
                            if (obstacleDetectedInFrame) {
                                obstacleFrameCount++

                                Log.d(
                                    "NAVIS_OBSTACLE",
                                    "Potential obstacle frame: $obstacleFrameCount"
                                )

                                if (obstacleFrameCount >= obstacleConfirmationFrames) {
                                    val currentTime = System.currentTimeMillis()

                                    if (
                                        currentTime - lastObstacleWarningTime >=
                                        obstacleWarningCooldown
                                    ) {
                                        lastObstacleWarningTime = currentTime

                                        Log.d(
                                            "NAVIS_OBSTACLE",
                                            "OBSTACLE CONFIRMED"
                                        )

                                        announceObstacle("object")
                                    }

                                    obstacleFrameCount = 0
                                }
                            } else {
                                obstacleFrameCount = 0
                            }
                        }
                    }
                    .addOnFailureListener { exception ->

                        Log.e(
                            "NAVIS_OBJECT",
                            "Object detection failed",
                            exception
                        )
                    }
                    .addOnCompleteListener {

                        imageProxy.close()
                    }
            }

            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            try {

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

            } catch (e: Exception) {

                Log.e(
                    "NAVIS_CAMERA",
                    "Camera preview failed",
                    e
                )
            }

        }, ContextCompat.getMainExecutor(this))
    }
    private lateinit var textToSpeech: TextToSpeech
    private var interruptedRouteInstruction: String? = null
    private var interruptedRouteIndex: Int? = null
    private var currentRouteInstructions: List<String> = emptyList()
    private var plannedRoutePoints =
        mutableListOf<Pair<Double, Double>>()
    private lateinit var placesClient: PlacesClient
    private lateinit var preferences: SharedPreferences
    private var selectedLanguage = "English"
    private var selectedLanguageCode = "en-GB"
    private var waitingForLanguage = false

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private lateinit var navigationLocationCallback: LocationCallback

    private val navigationLocationRequest =
        LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        )
            .setMinUpdateIntervalMillis(3000L)
            .build()


    private var updateStatus: ((String) -> Unit)? = null
    private var updateResult: ((String) -> Unit)? = null
    private var updateDestination: ((String) -> Unit)? = null
    private var destinationLatitude: Double? = null
    private var destinationLongitude: Double? = null
    private var destinationName = ""
    private var waitingForDestinationConfirmation = false
    private var pendingDestinationName = ""
    private var englishDestinationInputActive = false
    private var destinationReached = false
    private var navigationActive = false
    private var offRouteWarningGiven = false
    private var offRouteMonitoringEnabled = false

    private val offRouteThreshold = 50f

    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    private var cameraPreviewView: PreviewView? = null
    private var cameraPermissionRequested = false

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->

            if (isGranted) {
                updateStatus?.invoke("Camera ready")

                cameraPreviewView?.let { previewView ->
                    startCamera(previewView)
                }

            } else {
                updateStatus?.invoke("Camera permission denied")

                speak(
                    "Camera permission is required for obstacle detection"
                )
            }
        }



    // -------------------------
    // VOICE INPUT
    // -------------------------

    private val voiceInputLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK) {

                val data = result.data

                val results =
                    data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )

                val spokenText = results?.firstOrNull()
                Log.d(
                    "NAVIS_VOICE",
                    "All speech results: $results"
                )

                if (!spokenText.isNullOrEmpty()) {
                    val stopCommand =
                        spokenText.trim().equals("stop", ignoreCase = true) ||
                                spokenText.trim().equals("रुकें", ignoreCase = true) ||
                                spokenText.trim().equals("रुको", ignoreCase = true) ||
                                spokenText.trim().equals("நிறுத்து", ignoreCase = true) ||
                                spokenText.trim().equals("ఆపు", ignoreCase = true) ||
                                spokenText.trim().equals("ఆపండి", ignoreCase = true) ||
                                spokenText.trim().equals("থামুন", ignoreCase = true) ||
                                spokenText.trim().equals("थांबा", ignoreCase = true)

                    if (stopCommand) {

                        textToSpeech.stop()

                        navigationActive = false
                        offRouteMonitoringEnabled = false

                        try {
                            fusedLocationClient.removeLocationUpdates(
                                navigationLocationCallback
                            )
                        } catch (e: Exception) {
                            Log.e(
                                "NAVIS_NAVIGATION",
                                "Could not stop navigation location monitoring",
                                e
                            )
                        }

                        interruptedRouteInstruction = null
                        interruptedRouteIndex = null
                        obstacleWarningActive = false

                        englishDestinationInputActive = false
                        waitingForDestinationConfirmation = false
                        pendingDestinationName = ""

                        updateStatus?.invoke(
                            "Navigation stopped"
                        )

                        updateResult?.invoke(
                            "Voice instructions stopped"
                        )

                        return@registerForActivityResult
                    }

                    if (englishDestinationInputActive) {

                        englishDestinationInputActive = false

                        updateStatus?.invoke(
                            "Searching destination..."
                        )

                        updateResult?.invoke(
                            spokenText
                        )

                        searchDestination(
                            spokenText.trim()
                        )

                        return@registerForActivityResult
                    }
                    if (waitingForDestinationConfirmation) {

                        val answer = spokenText.trim().lowercase(Locale.UK)

                        if (
                            answer == "yes" ||
                            answer == "हाँ" ||
                            answer == "हां" ||
                            answer == "ஆம்" ||
                            answer == "అవును" ||
                            answer == "হ্যাঁ" ||
                            answer == "होय"
                        ) {

                            waitingForDestinationConfirmation = false

                            updateStatus?.invoke(
                                "Destination confirmed"
                            )

                            searchDestination(
                                pendingDestinationName
                            )

                            pendingDestinationName = ""

                            return@registerForActivityResult
                        }

                        if (
                            answer == "no" ||
                            answer == "नहीं" ||
                            answer == "இல்லை" ||
                            answer == "కాదు" ||
                            answer == "না" ||
                            answer == "नाही"
                        ) {

                            waitingForDestinationConfirmation = false
                            pendingDestinationName = ""

                            updateStatus?.invoke(
                                "Please say your destination again"
                            )

                            speak(
                                "Okay. Please say your destination again."
                            )

                            return@registerForActivityResult
                        }
                    }


// Change language command
                    val changeLanguageCommand =
                        spokenText.trim().equals("change language", ignoreCase = true) ||
                                spokenText.trim() == "भाषा बदलें" ||
                                spokenText.trim() == "भाषा बदला" ||
                                spokenText.trim() == "மொழியை மாற்று" ||
                                spokenText.trim() == "மொழியை மாற்றுங்கள்" ||
                                spokenText.trim() == "భాషను మార్చు" ||
                                spokenText.trim() == "భాషను మార్చండి" ||
                                spokenText.trim() == "ভাষা পরিবর্তন করুন" ||
                                spokenText.trim() == "ভাষা বদলান" ||
                                spokenText.trim() == "भाषा बदला"

                    if (changeLanguageCommand) {
                        askToChangeLanguage()
                        return@registerForActivityResult
                    }

                    updateStatus?.invoke("Searching destination...")
                    updateResult?.invoke(spokenText)

                    val destination = spokenText
                        // English
                        .replace(Regex("(?i)^take me to the\\s+"), "")
                        .replace(Regex("(?i)^take me to\\s+"), "")
                        .replace(Regex("(?i)^navigate me to\\s+"), "")
                        .replace(Regex("(?i)^navigate to\\s+"), "")
                        .replace(Regex("(?i)^go to the\\s+"), "")
                        .replace(Regex("(?i)^go to\\s+"), "")
                        .replace(Regex("(?i)^get me to\\s+"), "")
                        .replace(Regex("(?i)^the\\s+"), "")


                        // Hindi
                        .replace(Regex("^मुझे\\s+(.+?)\\s+ले\\s+चलो$"), "$1")
                        .replace(Regex("^मुझे\\s+(.+?)\\s+ले\\s+जाओ$"), "$1")
                        .replace(Regex("^मुझे\\s+(.+?)\\s+ले\\s+जाइए$"), "$1")
                        .replace(Regex("^(.+?)\\s+जाओ$"), "$1")

                        // Tamil
                        .replace(Regex("^என்னை\\s+(.+?)\\s+அழைத்துச்\\s+செல்லுங்கள்$"), "$1")
                        .replace(Regex("^என்னை\\s+(.+?)\\s+அழைத்துச்\\s+செல்$"), "$1")

                        // Telugu
                        .replace(Regex("^నన్ను\\s+(.+?)\\s+తీసుకెళ్లండి$"), "$1")
                        .replace(Regex("^నన్ను\\s+(.+?)\\s+తీసుకెళ్ళండి$"), "$1")

                        // Bengali
                        .replace(Regex("^আমাকে\\s+(.+?)\\s+নিয়ে\\s+যান$"), "$1")
                        .replace(Regex("^আমাকে\\s+(.+?)\\s+নিয়ে\\s+চলো$"), "$1")

                        // Marathi
                        .replace(Regex("^मला\\s+(.+?)\\s+घेऊन\\s+जा$"), "$1")
                        .replace(Regex("^मला\\s+(.+?)\\s+घेऊन\\s+चला$"), "$1")

                        .trim()

                    searchDestination(destination)

                } else {

                    updateStatus?.invoke("No speech detected")
                    speak("I did not hear anything")
                }

            } else {

                updateStatus?.invoke("Ready")
            }
        }


    // -------------------------
    // LOCATION PERMISSION
    // -------------------------

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineLocation =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseLocation =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineLocation || coarseLocation) {

                updateStatus?.invoke("Location permission granted")
                getCurrentLocation()

            } else {

                updateStatus?.invoke("Location permission denied")
                speak("Location permission is required")
            }
        }


    // -------------------------
    // ON CREATE
    // -------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        preferences = getSharedPreferences(
            "NavIsPreferences",
            MODE_PRIVATE
        )

        selectedLanguage =
            preferences.getString(
                "selectedLanguage",
                ""
            ) ?: ""
        selectedLanguageCode =
            preferences.getString(
                "selectedLanguageCode",
                "en-GB"
            ) ?: "en-GB"

        // Initialize Google Places
        if (!Places.isInitialized()) {

            Places.initializeWithNewPlacesApiEnabled(
                applicationContext,
                BuildConfig.PLACES_API_KEY
            )
        }

        placesClient = Places.createClient(this)


        // Initialize Text To Speech
        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val savedLocale =
                    Locale.forLanguageTag(selectedLanguageCode)

                textToSpeech.language = savedLocale

                val savedVoice = textToSpeech.voices
                    .firstOrNull { voice ->
                        voice.locale == savedLocale &&
                                !voice.isNetworkConnectionRequired
                    }

                if (savedVoice != null) {
                    textToSpeech.voice = savedVoice
                }

                if (selectedLanguage.isEmpty()) {

                    textToSpeech.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {

                            override fun onStart(utteranceId: String?) {
                            }

                            override fun onDone(utteranceId: String?) {

                                if (utteranceId == "LANGUAGE_PROMPT") {

                                    runOnUiThread {
                                        startLanguageInput()
                                    }
                                }
                            }

                            override fun onError(utteranceId: String?) {
                            }
                        }
                    )

                    textToSpeech.speak(
                        "Welcome to NavIs. " +
                                "Voice navigation for safe and accessible travel.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "WELCOME"
                    )

                    textToSpeech.speak(
                        "Please say your language. " +
                                "You can say English, Hindi, Tamil, Telugu, Bengali, or Marathi.",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "LANGUAGE_PROMPT"
                    )

                } else {

                    speak(
                        "Welcome back to NavIs. " +
                                "Your selected language is $selectedLanguage."
                    )
                }
            }
        }


        // Compose UI
        setContent {

            var statusText by remember {
                mutableStateOf("Ready")
            }

            var recognizedText by remember {
                mutableStateOf("")
            }

            var destinationText by remember {
                mutableStateOf("")
            }

            var locationText by remember {
                mutableStateOf("")
            }


            updateStatus = {
                statusText = it
            }

            updateResult = {
                recognizedText = it
            }

            updateDestination = {
                destinationText = it
            }


            NavIsScreen(
                statusText = statusText,
                recognizedText = recognizedText,
                destinationText = destinationText,
                locationText = locationText,

                onScreenTap = {
                    startVoiceInput()
                },

                onLocationTap = {
                    requestLocation()
                },

                onLanguageSelected = { language, languageCode ->
                    selectLanguageFromScreen(language, languageCode)
                },

                cameraPreview = {
                    AndroidView(
                        factory = { context ->
                            PreviewView(context).apply {

                                cameraPreviewView = this

                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { previewView ->
                            startCamera(previewView)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }
    }


    // -------------------------
    // VOICE INPUT
    // -------------------------

    private fun startVoiceInput() {

        textToSpeech.stop()

        val languageLocale =
            Locale.forLanguageTag(selectedLanguageCode)

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                languageLocale.toLanguageTag()
            )

            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak your destination"
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )
        }

        try {

            updateStatus?.invoke(
                "Listening... Input language: $selectedLanguageCode"
            )

            voiceInputLauncher.launch(intent)

        } catch (e: Exception) {

            updateStatus?.invoke(
                "Voice input unavailable"
            )

            speak(
                "Voice input is unavailable"
            )
        }
    }

    private fun startEnglishDestinationInput() {
        englishDestinationInputActive = true

        textToSpeech.stop()

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "en-IN"
            )

            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Please say the destination name"
            )
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )
        }

        try {

            updateStatus?.invoke(
                "Please say the destination name in English"
            )

            textToSpeech.speak(
                "Please say the destination name in English",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ENGLISH_DESTINATION_PROMPT"
            )

            textToSpeech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(
                        utteranceId: String?
                    ) {
                    }

                    override fun onDone(
                        utteranceId: String?
                    ) {
                        if (
                            utteranceId ==
                            "ENGLISH_DESTINATION_PROMPT"
                        ) {
                            runOnUiThread {
                                voiceInputLauncher.launch(intent)
                            }
                        }
                    }

                    override fun onError(
                        utteranceId: String?
                    ) {
                        runOnUiThread {
                            voiceInputLauncher.launch(intent)
                        }
                    }
                }
            )

        } catch (e: Exception) {

            updateStatus?.invoke(
                "Voice input unavailable"
            )

            speak(
                "Voice input is unavailable"
            )
        }
    }

    private fun startLanguageInput() {

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.ENGLISH
            )

            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Please say your language"
            )
        }

        try {

            waitingForLanguage = true

            updateStatus?.invoke("Listening for language...")

            voiceInputLauncher.launch(intent)

        } catch (e: Exception) {

            updateStatus?.invoke(
                "Language voice input unavailable"
            )
        }
    }

    private fun askToChangeLanguage() {

        textToSpeech.stop()

        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(utteranceId: String?) {
                }

                override fun onDone(utteranceId: String?) {

                    if (utteranceId == "CHANGE_LANGUAGE_PROMPT") {

                        runOnUiThread {
                            startLanguageInput()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                }
            }
        )

        textToSpeech.speak(
            "Please say your new language. " +
                    "You can say English, Hindi, Tamil, Telugu, Bengali, or Marathi.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "CHANGE_LANGUAGE_PROMPT"
        )
    }

    private fun handleLanguageSelection(spokenText: String) {

        val language = spokenText
            .lowercase(Locale.UK)
            .trim()

        val selection = when {

            language.contains("english") &&
                    (language.contains("india") ||
                            language.contains("indian")) -> {
                "English (India)" to "en-IN"
            }

            language.contains("english") -> {
                "English (UK)" to "en-GB"
            }

            language.contains("hindi") -> {
                "Hindi" to "hi-IN"
            }

            language.contains("tamil") -> {
                "Tamil" to "ta-IN"
            }

            language.contains("telugu") -> {
                "Telugu" to "te-IN"
            }

            language.contains("bengali") -> {
                "Bengali" to "bn-IN"
            }

            language.contains("marathi") -> {
                "Marathi" to "mr-IN"
            }

            else -> null
        }

        if (selection == null) {

            waitingForLanguage = false

            updateStatus?.invoke("Language not recognized")

            speak(
                "I did not recognize that language. " +
                        "Please say English, Hindi, Tamil, Telugu, Bengali, or Marathi."
            )

            return
        }

        val languageName = selection.first
        val languageCode = selection.second

        selectedLanguage = languageName
        selectedLanguageCode = languageCode

        preferences.edit()
            .putString(
                "selectedLanguage",
                languageName
            )
            .putString(
                "selectedLanguageCode",
                languageCode
            )
            .apply()

        waitingForLanguage = false

        val locale = Locale.forLanguageTag(languageCode)

        val result = textToSpeech.setLanguage(locale)
        Log.d(
            "NAVIS_TTS",
            "Selected locale: $locale"
        )

        textToSpeech.voices
            .filter { it.locale.language == locale.language }
            .forEach {
                Log.d(
                    "NAVIS_TTS",
                    "Voice: ${it.name}, locale=${it.locale}, network=${it.isNetworkConnectionRequired}"
                )
            }
        val matchingVoice = textToSpeech.voices
            .firstOrNull { voice ->
                voice.locale == locale &&
                        !voice.isNetworkConnectionRequired
            }

        if (matchingVoice != null) {
            textToSpeech.voice = matchingVoice
        }

        updateStatus?.invoke(
            "Language selected: $languageName"
        )

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {

            textToSpeech.language = Locale.UK

            speak(
                "$languageName selected, " +
                        "but this language is not available for speech on this device."
            )

        } else {

            speak(
                "$languageName selected. " +
                        "NavIs is ready."
            )
        }
    }




    // -------------------------
    // SEARCH DESTINATION
    // -------------------------

    private fun searchDestination(
        query: String,
    ) {

        updateStatus?.invoke("Searching for $query")

        speak("Searching for $query")

        val requestBuilder =
            FindAutocompletePredictionsRequest.builder()
                .setQuery(query)


        val request = requestBuilder.build()


        placesClient
            .findAutocompletePredictions(request)

            .addOnSuccessListener { response ->

                val predictions =
                    response.autocompletePredictions
                        .take(3)





                if (predictions.isEmpty()) {
                    // keep your existing "destination not found" code here
                    return@addOnSuccessListener
                }

                val prediction = predictions.first()
                val placeId = prediction.placeId

                val placeName =
                    prediction.getFullText(null).toString()


                val placeRequest =
                    FetchPlaceRequest.builder(
                        placeId,
                        listOf(
                            Place.Field.ID,
                            Place.Field.DISPLAY_NAME,
                            Place.Field.LOCATION
                        )
                    ).build()


                placesClient
                    .fetchPlace(placeRequest)

                    .addOnSuccessListener { placeResponse ->

                        val place =
                            placeResponse.place

                        val location =
                            place.location


                        if (location != null) {

                            val latitude =
                                location.latitude

                            val longitude =
                                location.longitude

                            destinationName = placeName
                            destinationLatitude = latitude
                            destinationLongitude = longitude
                            destinationReached = false
                            offRouteWarningGiven = false
                            offRouteMonitoringEnabled = false

                            updateStatus?.invoke(
                                "Destination found"
                            )

                            updateDestination?.invoke(
                                "Destination: $placeName"
                            )

                            speak(
                                "Destination found. " +
                                        "You selected $placeName"
                            )

                            speakQueued(
                                "Getting your current location"
                            )

                            updateStatus?.invoke(
                                "Getting your current location..."
                            )

                            requestLocation()

                        } else {

                            updateStatus?.invoke(
                                "Destination location unavailable"
                            )

                            speak(
                                "I found the destination, " +
                                        "but its location is unavailable"
                            )
                        }
                    }

                    .addOnFailureListener {

                        updateStatus?.invoke(
                            "Unable to get destination location"
                        )

                        speak(
                            "I found the destination, " +
                                    "but could not get its location"
                        )
                    }
            }



            .addOnFailureListener {

                updateStatus?.invoke(
                    "Destination search failed"
                )

                speak(
                    "I could not search for that destination"
                )
            }
    }


    // -------------------------
    // LOCATION PERMISSION
    // -------------------------

    private fun requestLocation() {

        val finePermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED


        val coarsePermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED


        if (finePermission || coarsePermission) {

            getCurrentLocation()

        } else {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }


    // -------------------------
    // GET CURRENT LOCATION
    // -------------------------

    private fun getCurrentLocation() {

        updateStatus?.invoke(
            "Getting your current location..."
        )

        try {

            fusedLocationClient.lastLocation

                .addOnSuccessListener { location: Location? ->

                    if (location != null) {

                        val latitude =
                            location.latitude

                        val longitude =
                            location.longitude

                        currentLatitude = latitude
                        currentLongitude = longitude

                        checkDestinationReached(
                            latitude,
                            longitude
                        )

                        if (
                            destinationLatitude != null &&
                            destinationLongitude != null
                        ) {

                            updateStatus?.invoke(
                                "Finding walking route..."
                            )

                            speakQueued(
                                "Finding a walking route to your destination"
                            )

                            calculateWalkingRoute(
                                latitude,
                                longitude,
                                destinationLatitude!!,
                                destinationLongitude!!
                            )

                        } else {

                            updateStatus?.invoke(
                                "Location detected"
                            )
                        }

                    } else {

                        updateStatus?.invoke(
                            "Location not available"
                        )

                        speak(
                            "I could not determine your location"
                        )
                    }
                }

                .addOnFailureListener {

                    updateStatus?.invoke(
                        "Unable to get location"
                    )

                    speak(
                        "Unable to get your current location"
                    )
                }

        } catch (e: SecurityException) {

            updateStatus?.invoke(
                "Location permission required"
            )

            speak(
                "Location permission is required"
            )
        }
    }

    private fun startNavigationLocationMonitoring() {

        navigationLocationCallback =
            object : LocationCallback() {

                override fun onLocationResult(
                    locationResult: LocationResult
                ) {

                    if (!navigationActive) {
                        return
                    }

                    val location =
                        locationResult.lastLocation
                            ?: return

                    currentLatitude = location.latitude
                    currentLongitude = location.longitude

                    Log.d(
                        "NAVIS_NAVIGATION",
                        "Navigation location: " +
                                "${location.latitude}, " +
                                "${location.longitude}"
                    )

                    checkDestinationReached(
                        location.latitude,
                        location.longitude
                    )

                    checkIfOffRoute(
                        location.latitude,
                        location.longitude
                    )
                }
            }

        try {

            fusedLocationClient.requestLocationUpdates(
                navigationLocationRequest,
                navigationLocationCallback,
                Looper.getMainLooper()
            )

        } catch (e: SecurityException) {

            Log.e(
                "NAVIS_NAVIGATION",
                "Location monitoring permission error",
                e
            )
        }
    }

    private fun checkIfOffRoute(
        latitude: Double,
        longitude: Double
    ) {
        if (!offRouteMonitoringEnabled) {
            return
        }

        if (plannedRoutePoints.isEmpty()) {
            return
        }

        var minimumDistance = Float.MAX_VALUE

        for (point in plannedRoutePoints) {

            val results = FloatArray(1)

            Location.distanceBetween(
                latitude,
                longitude,
                point.first,
                point.second,
                results
            )

            if (results[0] < minimumDistance) {
                minimumDistance = results[0]
            }
        }

        Log.d(
            "NAVIS_OFF_ROUTE",
            "Distance from planned route: $minimumDistance meters"
        )
        if (
            minimumDistance > offRouteThreshold &&
            !offRouteWarningGiven
        ) {

            offRouteWarningGiven = true

            val currentInstruction =
                interruptedRouteInstruction

            textToSpeech.speak(
                "Warning. You are off route. Please return to the planned walking route.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "OFF_ROUTE_WARNING"
            )

            updateStatus?.invoke(
                "You are off route"
            )

            updateResult?.invoke(
                "You are more than " +
                        "${offRouteThreshold.toInt()} meters " +
                        "from the planned route."
            )
        }
    }

    private fun checkDestinationReached(
        latitude: Double,
        longitude: Double
    ) {

        val destinationLat = destinationLatitude
            ?: return

        val destinationLon = destinationLongitude
            ?: return

        val results = FloatArray(1)

        Location.distanceBetween(
            latitude,
            longitude,
            destinationLat,
            destinationLon,
            results
        )

        val distanceInMeters = results[0]

        Log.d(
            "NAVIS_DESTINATION",
            "Distance to destination: $distanceInMeters meters"
        )

        val distanceText =
            if (distanceInMeters >= 1000f) {
                "%.2f km".format(
                    distanceInMeters / 1000f
                )
            } else {
                "%.0f meters".format(
                    distanceInMeters
                )
            }

        updateResult?.invoke(
            "Distance to destination: $distanceText"
        )

        if (
            distanceInMeters <= 30f &&
            !destinationReached
        ) {

            destinationReached = true
            navigationActive = false
            offRouteMonitoringEnabled = false

            try {
                fusedLocationClient.removeLocationUpdates(
                    navigationLocationCallback
                )
            } catch (e: Exception) {
                Log.e(
                    "NAVIS_NAVIGATION",
                    "Could not stop navigation location monitoring",
                    e
                )
            }

            textToSpeech.stop()

            updateStatus?.invoke(
                "Destination reached"
            )

            updateResult?.invoke(
                "Destination reached.\n" +
                        "Distance: $distanceText"
            )

            speak(
                "You have reached your destination."
            )
        }
    }

    private fun calculateWalkingRoute(
        originLat: Double,
        originLon: Double,
        destinationLat: Double,
        destinationLon: Double
    ) {
        Thread {
            try {
                val url = java.net.URL(
                    "https://valhalla1.openstreetmap.de/route"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
                connection.setRequestProperty(
                    "X-Client-Id",
                    "NavIs-Android-Demo"
                )

                val json = """
                {
                    "locations": [
                        {
                            "lat": $originLat,
                            "lon": $originLon
                        },
                        {
                            "lat": $destinationLat,
                            "lon": $destinationLon
                        }
                    ],
                    "costing": "pedestrian"
                }
            """.trimIndent()

                connection.outputStream.use { output ->
                    output.write(json.toByteArray())
                }

                val responseCode = connection.responseCode

                val responseText =
                    if (responseCode in 200..299) {
                        connection.inputStream.bufferedReader().use {
                            it.readText()
                        }
                    } else {
                        connection.errorStream?.bufferedReader()?.use {
                            it.readText()
                        } ?: "Route request failed"
                    }

                Log.d("NAVIS_ROUTE", responseText)

                runOnUiThread {
                    if (responseCode in 200..299) {

                        navigationActive = true

                        offRouteWarningGiven = false

                        startNavigationLocationMonitoring()

                        Handler(Looper.getMainLooper()).postDelayed(
                            {
                                if (navigationActive) {
                                    updateStatus?.invoke(
                                        "Off-route monitoring active"
                                    )
                                }
                            },
                            15000L
                        )
                        updateStatus?.invoke("Walking route found")

                        speak("Walking route found")

                        speakRouteInstructions(responseText)
                        plannedRoutePoints.clear()

                        try {
                            val routeJson =
                                org.json.JSONObject(responseText)

                            val trip =
                                routeJson.getJSONObject("trip")

                            val legs =
                                trip.getJSONArray("legs")

                            val firstLeg =
                                legs.getJSONObject(0)

                            val shape =
                                firstLeg.getString("shape")

                            val decodedShape =
                                com.google.maps.android.PolyUtil.decode(shape)

                            for (point in decodedShape) {
                                plannedRoutePoints.add(
                                    Pair(
                                        point.latitude,
                                        point.longitude
                                    )
                                )
                            }

                            Log.d(
                                "NAVIS_ROUTE",
                                "Planned route points: ${plannedRoutePoints.size}"
                            )

                        } catch (e: Exception) {

                            Log.e(
                                "NAVIS_ROUTE",
                                "Could not extract route points",
                                e
                            )
                        }

                        val distanceResults = FloatArray(1)

                        if (
                            currentLatitude != null &&
                            currentLongitude != null &&
                            destinationLatitude != null &&
                            destinationLongitude != null
                        ) {

                            Location.distanceBetween(
                                currentLatitude!!,
                                currentLongitude!!,
                                destinationLatitude!!,
                                destinationLongitude!!,
                                distanceResults
                            )

                            val distanceInMeters = distanceResults[0]

                            val distanceText =
                                if (distanceInMeters >= 1000f) {
                                    "%.2f km".format(
                                        distanceInMeters / 1000f
                                    )
                                } else {
                                    "%.0f meters".format(
                                        distanceInMeters
                                    )
                                }

                            updateResult?.invoke(
                                "Destination: $destinationName\n\n" +
                                        "Walking route calculated.\n\n" +
                                        "Distance to destination: $distanceText"
                            )
                        }
                    } else {
                        Log.e(
                            "NAVIS_ROUTE",
                            "HTTP $responseCode: $responseText"
                        )

                        updateStatus?.invoke("Route unavailable")

                        updateResult?.invoke(
                            "Route request failed.\n\n" +
                                    "HTTP code: $responseCode\n" +
                                    responseText
                        )

                        speak("I could not find a walking route")
                    }
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e("NAVIS_ROUTE", "Route error", e)

                runOnUiThread {
                    updateStatus?.invoke("Route error")
                    speak("There was a problem finding the walking route")
                }
            }
        }.start()
    }

    private fun translateNavigationInstruction(
        instruction: String
    ): String {

        return when (selectedLanguageCode) {

            "hi-IN" -> {
                instruction
                    .replace(
                        Regex("^Walk north\\.?$", RegexOption.IGNORE_CASE),
                        "उत्तर दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk south\\.?$", RegexOption.IGNORE_CASE),
                        "दक्षिण दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk east\\.?$", RegexOption.IGNORE_CASE),
                        "पूर्व दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk west\\.?$", RegexOption.IGNORE_CASE),
                        "पश्चिम दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk northeast\\.?$", RegexOption.IGNORE_CASE),
                        "उत्तर-पूर्व दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk northwest\\.?$", RegexOption.IGNORE_CASE),
                        "उत्तर-पश्चिम दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk southeast\\.?$", RegexOption.IGNORE_CASE),
                        "दक्षिण-पूर्व दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Walk southwest\\.?$", RegexOption.IGNORE_CASE),
                        "दक्षिण-पश्चिम दिशा में आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Turn right\\.?$", RegexOption.IGNORE_CASE),
                        "दाएं मुड़ें"
                    )
                    .replace(
                        Regex("^Turn left\\.?$", RegexOption.IGNORE_CASE),
                        "बाएं मुड़ें"
                    )
                    .replace(
                        Regex("Keep left to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} लेने के लिए बाईं ओर रहें"
                    }
                    .replace(
                        Regex("Keep right to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} लेने के लिए दाईं ओर रहें"
                    }
                    .replace(
                        Regex("Bear left(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर बाईं तरफ रहें"
                    }
                    .replace(
                        Regex("Bear right(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर दाईं तरफ रहें"
                    }
                    .replace(
                        Regex("Turn right onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर दाएं मुड़ें"
                    }
                    .replace(
                        Regex("Turn left onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर बाएं मुड़ें"
                    }
                    .replace("Slight right", "थोड़ा दाएं मुड़ें", ignoreCase = true)
                    .replace("Slight left", "थोड़ा बाएं मुड़ें", ignoreCase = true)
                    .replace(
                        Regex("^Continue straight\\.?$", RegexOption.IGNORE_CASE),
                        "सीधे आगे बढ़ते रहें"
                    )
                    .replace(
                        Regex("^Continue straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर सीधे आगे बढ़ते रहें"
                    }
                    .replace(
                        Regex("^Continue straight onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर सीधे आगे बढ़ते रहें"
                    }
                    .replace(
                        Regex("Continue (?:onto|on) (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर आगे बढ़ते रहें"
                    }
                    .replace(
                        Regex("Keep right to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "दाईं ओर रहें और ${it.groupValues[1]} पर बने रहें"
                    }
                    .replace(
                        Regex("Keep left to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "बाईं ओर रहें और ${it.groupValues[1]} पर बने रहें"
                    }
                    .replace("Make a U-turn", "यू-टर्न लें", ignoreCase = true)
                    .replace("U-turn", "यू-टर्न", ignoreCase = true)
                    .replace(
                        Regex(
                            "Enter the roundabout and take the (\\d+)(?:st|nd|rd|th) exit",
                            RegexOption.IGNORE_CASE
                        )
                    ) {
                        "गोल चक्कर में प्रवेश करें और ${it.groupValues[1]}वें निकास से बाहर निकलें"
                    }
                    .replace("Enter the roundabout", "गोल चक्कर में प्रवेश करें", ignoreCase = true)
                    .replace("Exit the roundabout", "गोल चक्कर से बाहर निकलें", ignoreCase = true)
                    .replace("roundabout", "गोल चक्कर", ignoreCase = true)
                    .replace(
                        Regex("Your destination is on the left\\.?$", RegexOption.IGNORE_CASE),
                        "आपका गंतव्य बाईं ओर है"
                    )
                    .replace(
                        Regex("Your destination is on the right\\.?$", RegexOption.IGNORE_CASE),
                        "आपका गंतव्य दाईं ओर है"
                    )
                    .replace(
                        Regex("Keep left (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "जहाँ रास्ता दो भागों में बंटता है, वहाँ बाईं ओर रहें"
                    )
                    .replace(
                        Regex("Keep right (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "जहाँ रास्ता दो भागों में बंटता है, वहाँ दाईं ओर रहें"
                    )
                    .replace(
                        Regex(
                            "You have arrived at your destination\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "आप अपने गंतव्य पर पहुंच गए हैं।"
                    )
                    .replace(
                        Regex(
                            "Stay straight to take the ramp\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "रैंप लेने के लिए सीधे चलते रहें।"
                    )
                    .replace(
                        Regex("^Take the ramp\\.?$", RegexOption.IGNORE_CASE),
                        "रैंप पर जाएं"
                    )
                    .replace(
                        Regex("Merge onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर मिल जाएं"
                    }
                    .replace(
                        Regex("^Proceed straight\\.?$", RegexOption.IGNORE_CASE),
                        "सीधे आगे बढ़ें"
                    )
                    .replace(
                        Regex("^Proceed straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर सीधे आगे बढ़ें"
                    }

                    .replace(
                        Regex("Follow (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} का रास्ता अपनाएं"
                    }

                    .replace(
                        Regex("Cross (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} को पार करें"
                    }

                    .replace(
                        Regex("At the traffic light", RegexOption.IGNORE_CASE),
                        "ट्रैफिक सिग्नल पर"
                    )

                    .replace(
                        Regex("At the intersection", RegexOption.IGNORE_CASE),
                        "चौराहे पर"
                    )

                    .replace(
                        Regex("At the fork", RegexOption.IGNORE_CASE),
                        "जहाँ रास्ता दो भागों में बंटता है"
                    )

                    .replace(
                        Regex("Use the sidewalk", RegexOption.IGNORE_CASE),
                        "फुटपाथ का उपयोग करें"
                    )

                    .replace(
                        Regex("Take the sidewalk", RegexOption.IGNORE_CASE),
                        "फुटपाथ पर चलें"
                    )

                    .replace(
                        Regex("Keep left on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर बाईं ओर रहें"
                    }

                    .replace(
                        Regex("Keep right on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर दाईं ओर रहें"
                    }

                    .replace(
                        Regex("^Bear left\\.?$", RegexOption.IGNORE_CASE),
                        "बाईं ओर मुड़ें"
                    )

                    .replace(
                        Regex("^Bear right\\.?$", RegexOption.IGNORE_CASE),
                        "दाईं ओर मुड़ें"
                    )

                    .replace(
                        Regex("Turn left at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर बाएं मुड़ें"
                    }

                    .replace(
                        Regex("Turn right at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर दाएं मुड़ें"
                    }

                    .replace(
                        Regex("Turn left toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर बाएं मुड़ें"
                    }

                    .replace(
                        Regex("Turn right toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर दाएं मुड़ें"
                    }

                    .replace(
                        Regex("Take the (\\d+)(?:st|nd|rd|th) exit", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}वें निकास से बाहर निकलें"
                    }

                    .replace(
                        Regex("Take exit (\\d+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}वें निकास से बाहर निकलें"
                    }
                    .replace(
                        Regex("Walk along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} के साथ आगे बढ़ें"
                    }

                    .replace(
                        Regex("Walk past (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} को पार करते हुए आगे बढ़ें"
                    }

                    .replace(
                        Regex("Pass (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} को पार करें"
                    }

                    .replace(
                        Regex("Cross the road", RegexOption.IGNORE_CASE),
                        "सड़क पार करें"
                    )

                    .replace(
                        Regex("Cross the street", RegexOption.IGNORE_CASE),
                        "सड़क पार करें"
                    )

                    .replace(
                        Regex("At the next intersection", RegexOption.IGNORE_CASE),
                        "अगले चौराहे पर"
                    )

                    .replace(
                        Regex("At the next traffic light", RegexOption.IGNORE_CASE),
                        "अगले ट्रैफिक सिग्नल पर"
                    )

                    .replace(
                        Regex("Take the next left", RegexOption.IGNORE_CASE),
                        "अगले मोड़ पर बाएं मुड़ें"
                    )

                    .replace(
                        Regex("Take the next right", RegexOption.IGNORE_CASE),
                        "अगले मोड़ पर दाएं मुड़ें"
                    )

                    .replace(
                        Regex("Continue for (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} तक आगे बढ़ते रहें"
                    }
                    .replace(
                        Regex("Turn slightly left", RegexOption.IGNORE_CASE),
                        "थोड़ा बाएं मुड़ें"
                    )

                    .replace(
                        Regex("Turn slightly right", RegexOption.IGNORE_CASE),
                        "थोड़ा दाएं मुड़ें"
                    )

                    .replace(
                        Regex("Continue along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} के साथ आगे बढ़ते रहें"
                    }

                    .replace(
                        Regex("Head toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर आगे बढ़ें"
                    }

                    .replace(
                        Regex("Head towards (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर आगे बढ़ें"
                    }

                    .replace(
                        Regex("Take the stairs", RegexOption.IGNORE_CASE),
                        "सीढ़ियों का उपयोग करें"
                    )

                    .replace(
                        Regex("Take the stairs to (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} तक पहुंचने के लिए सीढ़ियों का उपयोग करें"
                    }

                    .replace(
                        Regex("Take the path", RegexOption.IGNORE_CASE),
                        "रास्ते पर आगे बढ़ें"
                    )

                    .replace(
                        Regex("Take the path toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} की ओर रास्ते पर आगे बढ़ें"
                    }
                    .replace("Destination", "गंतव्य", ignoreCase = true)
                    .replace("Start", "शुरू करें", ignoreCase = true)
                    .replace("Enter", "प्रवेश करें", ignoreCase = true)
                    .replace("Exit", "बाहर निकलें", ignoreCase = true)
                    .replace("meters", "मीटर", ignoreCase = true)
                    .replace("meter", "मीटर", ignoreCase = true)
                    .replace("kilometers", "किलोमीटर", ignoreCase = true)
                    .replace("kilometer", "किलोमीटर", ignoreCase = true)
            }

            "ta-IN" -> {
                instruction
                    .replace(
                        Regex("^Walk north\\.?$", RegexOption.IGNORE_CASE),
                        "வடக்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk south\\.?$", RegexOption.IGNORE_CASE),
                        "தெற்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk east\\.?$", RegexOption.IGNORE_CASE),
                        "கிழக்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk west\\.?$", RegexOption.IGNORE_CASE),
                        "மேற்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk northeast\\.?$", RegexOption.IGNORE_CASE),
                        "வடகிழக்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk northwest\\.?$", RegexOption.IGNORE_CASE),
                        "வடமேற்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk southeast\\.?$", RegexOption.IGNORE_CASE),
                        "தென்கிழக்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Walk southwest\\.?$", RegexOption.IGNORE_CASE),
                        "தென்மேற்கு திசையில் முன்னே செல்லுங்கள்"
                    )
                    .replace(
                        Regex("^Turn right\\.?$", RegexOption.IGNORE_CASE),
                        "வலதுபுறம் திரும்புங்கள்"
                    )
                    .replace(
                        Regex("^Turn left\\.?$", RegexOption.IGNORE_CASE),
                        "இடதுபுறம் திரும்புங்கள்"
                    )
                    .replace(
                        Regex("Keep left to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} செல்ல இடதுபுறமாக இருங்கள்"
                    }
                    .replace(
                        Regex("Keep right to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} செல்ல வலதுபுறமாக இருங்கள்"
                    }
                    .replace(
                        Regex("Bear left(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி இடதுபுறமாக செல்லுங்கள்"
                    }
                    .replace(
                        Regex("Bear right(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி வலதுபுறமாக செல்லுங்கள்"
                    }
                    .replace(
                        Regex("Turn right onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} சாலையில் வலதுபுறம் திரும்புங்கள்"
                    }
                    .replace(
                        Regex("Turn left onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} சாலையில் இடதுபுறம் திரும்புங்கள்"
                    }
                    .replace("Slight right", "சற்று வலதுபுறம் திரும்புங்கள்", ignoreCase = true)
                    .replace("Slight left", "சற்று இடதுபுறம் திரும்புங்கள்", ignoreCase = true)
                    .replace(
                        Regex("Continue (?:onto|on) (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} சாலையில் தொடர்ந்து செல்லுங்கள்"
                    }
                    .replace(
                        Regex("Keep right to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} சாலையில் தொடர்ந்து செல்ல வலதுபுறமாக செல்லுங்கள்"
                    }
                    .replace(
                        Regex("Keep left to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} சாலையில் தொடர்ந்து செல்ல இடதுபுறமாக செல்லுங்கள்"
                    }
                    .replace("Make a U-turn", "யூ-டர்ன் எடுக்கவும்", ignoreCase = true)
                    .replace("U-turn", "யூ-டர்ன்", ignoreCase = true)
                    .replace(
                        Regex(
                            "Enter the roundabout and take the (\\d+)(?:st|nd|rd|th) exit",
                            RegexOption.IGNORE_CASE
                        )
                    ) {
                        "சுற்றுச்சாலைக்குள் சென்று ${it.groupValues[1]}வது வெளியேறும் பாதையில் வெளியேறுங்கள்"
                    }
                    .replace("Enter the roundabout", "சுற்றுச்சாலைக்குள் நுழையுங்கள்", ignoreCase = true)
                    .replace("Exit the roundabout", "சுற்றுச்சாலையிலிருந்து வெளியேறுங்கள்", ignoreCase = true)
                    .replace("roundabout", "சுற்றுச்சாலை", ignoreCase = true)
                    .replace(
                        Regex("Your destination is on the left\\.?$", RegexOption.IGNORE_CASE),
                        "உங்கள் இலக்கு இடதுபுறத்தில் உள்ளது"
                    )
                    .replace(
                        Regex("Your destination is on the right\\.?$", RegexOption.IGNORE_CASE),
                        "உங்கள் இலக்கு வலதுபுறத்தில் உள்ளது"
                    )
                    .replace(
                        Regex("Keep left (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "பாதை இரண்டாகப் பிரியும் இடத்தில் இடதுபுறமாக இருங்கள்"
                    )
                    .replace(
                        Regex("Keep right (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "பாதை இரண்டாகப் பிரியும் இடத்தில் வலதுபுறமாக இருங்கள்"
                    )
                    .replace(
                        Regex(
                            "You have arrived at your destination\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "நீங்கள் உங்கள் இலக்கை அடைந்துவிட்டீர்கள்."
                    )
                    .replace(
                        Regex(
                            "Stay straight to take the ramp\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "ரேம்பில் செல்ல நேராகத் தொடருங்கள்."
                    )
                    .replace(
                        Regex("^Continue straight\\.?$", RegexOption.IGNORE_CASE),
                        "நேராக தொடர்ந்து செல்லுங்கள்"
                    )

                    .replace(
                        Regex("^Continue straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் நேராக தொடர்ந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("^Continue straight onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் நேராக தொடர்ந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("^Take the ramp\\.?$", RegexOption.IGNORE_CASE),
                        "ரேம்பில் செல்லுங்கள்"
                    )

                    .replace(
                        Regex("Merge onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் இணைந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("^Proceed straight\\.?$", RegexOption.IGNORE_CASE),
                        "நேராக செல்லுங்கள்"
                    )

                    .replace(
                        Regex("^Proceed straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் நேராக செல்லுங்கள்"
                    }
                    .replace(
                        Regex("Follow (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} வழியைப் பின்பற்றுங்கள்"
                    }

                    .replace(
                        Regex("Cross (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ஐக் கடந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Cross the road", RegexOption.IGNORE_CASE),
                        "சாலையைக் கடந்து செல்லுங்கள்"
                    )

                    .replace(
                        Regex("Cross the street", RegexOption.IGNORE_CASE),
                        "சாலையைக் கடந்து செல்லுங்கள்"
                    )

                    .replace(
                        Regex("At the traffic light", RegexOption.IGNORE_CASE),
                        "போக்குவரத்து சிக்னலில்"
                    )

                    .replace(
                        Regex("At the intersection", RegexOption.IGNORE_CASE),
                        "சந்திப்பில்"
                    )

                    .replace(
                        Regex("At the fork", RegexOption.IGNORE_CASE),
                        "சாலை இரண்டாகப் பிரியும் இடத்தில்"
                    )

                    .replace(
                        Regex("At the next intersection", RegexOption.IGNORE_CASE),
                        "அடுத்த சந்திப்பில்"
                    )

                    .replace(
                        Regex("At the next traffic light", RegexOption.IGNORE_CASE),
                        "அடுத்த போக்குவரத்து சிக்னலில்"
                    )

                    .replace(
                        Regex("Take the next left", RegexOption.IGNORE_CASE),
                        "அடுத்த திருப்பத்தில் இடதுபுறம் திரும்புங்கள்"
                    )

                    .replace(
                        Regex("Take the next right", RegexOption.IGNORE_CASE),
                        "அடுத்த திருப்பத்தில் வலதுபுறம் திரும்புங்கள்"
                    )

                    .replace(
                        Regex("Turn left at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் இடதுபுறம் திரும்புங்கள்"
                    }

                    .replace(
                        Regex("Turn right at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் வலதுபுறம் திரும்புங்கள்"
                    }

                    .replace(
                        Regex("Turn left toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி இடதுபுறம் திரும்புங்கள்"
                    }

                    .replace(
                        Regex("Turn right toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி வலதுபுறம் திரும்புங்கள்"
                    }

                    .replace(
                        Regex("Keep left on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் இடதுபுறமாக இருங்கள்"
                    }

                    .replace(
                        Regex("Keep right on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ல் வலதுபுறமாக இருங்கள்"
                    }

                    .replace(
                        Regex("^Bear left\\.?$", RegexOption.IGNORE_CASE),
                        "இடதுபுறமாகச் செல்லுங்கள்"
                    )

                    .replace(
                        Regex("^Bear right\\.?$", RegexOption.IGNORE_CASE),
                        "வலதுபுறமாகச் செல்லுங்கள்"
                    )

                    .replace(
                        Regex("Turn slightly left", RegexOption.IGNORE_CASE),
                        "சற்று இடதுபுறம் திரும்புங்கள்"
                    )

                    .replace(
                        Regex("Turn slightly right", RegexOption.IGNORE_CASE),
                        "சற்று வலதுபுறம் திரும்புங்கள்"
                    )

                    .replace(
                        Regex("Continue along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} வழியாக தொடர்ந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Continue for (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} வரை தொடர்ந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Head toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Head towards (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Walk along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} வழியாக நடந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Walk past (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ஐக் கடந்து நடந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Pass (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-ஐக் கடந்து செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Use the sidewalk", RegexOption.IGNORE_CASE),
                        "நடைபாதையைப் பயன்படுத்துங்கள்"
                    )

                    .replace(
                        Regex("Take the sidewalk", RegexOption.IGNORE_CASE),
                        "நடைபாதையில் செல்லுங்கள்"
                    )

                    .replace(
                        Regex("Take the stairs", RegexOption.IGNORE_CASE),
                        "படிக்கட்டுகளைப் பயன்படுத்துங்கள்"
                    )

                    .replace(
                        Regex("Take the stairs to (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} செல்ல படிக்கட்டுகளைப் பயன்படுத்துங்கள்"
                    }

                    .replace(
                        Regex("Take the path", RegexOption.IGNORE_CASE),
                        "பாதையில் செல்லுங்கள்"
                    )

                    .replace(
                        Regex("Take the path toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} நோக்கி பாதையில் செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Take the (\\d+)(?:st|nd|rd|th) exit", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}வது வெளியேறும் பாதையில் செல்லுங்கள்"
                    }

                    .replace(
                        Regex("Take exit (\\d+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}வது வெளியேறும் பாதையில் செல்லுங்கள்"
                    }
                    .replace("Destination", "இலக்கு", ignoreCase = true)
                    .replace("Start", "தொடங்குங்கள்", ignoreCase = true)
                    .replace("Enter", "நுழையுங்கள்", ignoreCase = true)
                    .replace("Exit", "வெளியேறுங்கள்", ignoreCase = true)
                    .replace("meters", "மீட்டர்", ignoreCase = true)
                    .replace("meter", "மீட்டர்", ignoreCase = true)
                    .replace("kilometers", "கிலோமீட்டர்", ignoreCase = true)
                    .replace("kilometer", "கிலோமீட்டர்", ignoreCase = true)
            }

            "te-IN" -> {
                instruction
                    .replace(
                        Regex("^Walk north\\.?$", RegexOption.IGNORE_CASE),
                        "ఉత్తర దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk south\\.?$", RegexOption.IGNORE_CASE),
                        "దక్షిణ దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk east\\.?$", RegexOption.IGNORE_CASE),
                        "తూర్పు దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk west\\.?$", RegexOption.IGNORE_CASE),
                        "పడమర దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk northeast\\.?$", RegexOption.IGNORE_CASE),
                        "ఈశాన్య దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk northwest\\.?$", RegexOption.IGNORE_CASE),
                        "వాయువ్య దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk southeast\\.?$", RegexOption.IGNORE_CASE),
                        "ఆగ్నేయ దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Walk southwest\\.?$", RegexOption.IGNORE_CASE),
                        "నైరుతి దిశగా ముందుకు నడవండి"
                    )
                    .replace(
                        Regex("^Turn right\\.?$", RegexOption.IGNORE_CASE),
                        "కుడివైపు తిరగండి"
                    )
                    .replace(
                        Regex("^Turn left\\.?$", RegexOption.IGNORE_CASE),
                        "ఎడమవైపు తిరగండి"
                    )
                    .replace(
                        Regex("Keep left to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}కి వెళ్లడానికి ఎడమవైపు ఉండండి"
                    }
                    .replace(
                        Regex("Keep right to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}కి వెళ్లడానికి కుడివైపు ఉండండి"
                    }
                    .replace(
                        Regex("Bear left(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు ఎడమవైపు వెళ్లండి"
                    }
                    .replace(
                        Regex("Bear right(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు కుడివైపు వెళ్లండి"
                    }
                    .replace(
                        Regex("Turn right onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై కుడివైపు తిరగండి"
                    }
                    .replace(
                        Regex("Turn left onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై ఎడమవైపు తిరగండి"
                    }
                    .replace("Slight right", "కొద్దిగా కుడివైపు తిరగండి", ignoreCase = true)
                    .replace("Slight left", "కొద్దిగా ఎడమవైపు తిరగండి", ignoreCase = true)
                    .replace(
                        Regex("Continue (?:onto|on) (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై కొనసాగండి"
                    }
                    .replace(
                        Regex("Keep right to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై కొనసాగడానికి కుడివైపు ఉండండి"
                    }
                    .replace(
                        Regex("Keep left to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై కొనసాగడానికి ఎడమవైపు ఉండండి"
                    }
                    .replace("Make a U-turn", "యూ-టర్న్ తీసుకోండి", ignoreCase = true)
                    .replace("U-turn", "యూ-టర్న్", ignoreCase = true)
                    .replace(
                        Regex(
                            "Enter the roundabout and take the (\\d+)(?:st|nd|rd|th) exit",
                            RegexOption.IGNORE_CASE
                        )
                    ) {
                        "రౌండబౌట్‌లోకి ప్రవేశించి ${it.groupValues[1]}వ నిష్క్రమణలో బయటకు వెళ్లండి"
                    }
                    .replace("Enter the roundabout", "రౌండబౌట్‌లోకి ప్రవేశించండి", ignoreCase = true)
                    .replace("Exit the roundabout", "రౌండబౌట్ నుండి బయటకు వెళ్లండి", ignoreCase = true)
                    .replace("roundabout", "రౌండబౌట్", ignoreCase = true)
                    .replace(
                        Regex("Your destination is on the left\\.?$", RegexOption.IGNORE_CASE),
                        "మీ గమ్యస్థానం ఎడమవైపున ఉంది"
                    )
                    .replace(
                        Regex("Your destination is on the right\\.?$", RegexOption.IGNORE_CASE),
                        "మీ గమ్యస్థానం కుడివైపున ఉంది"
                    )
                    .replace(
                        Regex("Keep left (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "దారి రెండుగా విడిపోయే చోట ఎడమవైపు ఉండండి"
                    )
                    .replace(
                        Regex("Keep right (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "దారి రెండుగా విడిపోయే చోట కుడివైపు ఉండండి"
                    )
                    .replace(
                        Regex(
                            "You have arrived at your destination\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "మీరు మీ గమ్యస్థానానికి చేరుకున్నారు."
                    )
                    .replace(
                        Regex(
                            "Stay straight to take the ramp\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "ర్యాంప్‌కి వెళ్లడానికి నేరుగా కొనసాగండి."
                    )
                    .replace(
                        Regex("^Continue straight\\.?$", RegexOption.IGNORE_CASE),
                        "నేరుగా కొనసాగండి"
                    )

                    .replace(
                        Regex("^Continue straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై నేరుగా కొనసాగండి"
                    }

                    .replace(
                        Regex("^Continue straight onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై నేరుగా కొనసాగండి"
                    }

                    .replace(
                        Regex("^Take the ramp\\.?$", RegexOption.IGNORE_CASE),
                        "రాంప్‌పైకి వెళ్లండి"
                    )

                    .replace(
                        Regex("Merge onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}లో కలవండి"
                    }

                    .replace(
                        Regex("^Proceed straight\\.?$", RegexOption.IGNORE_CASE),
                        "నేరుగా వెళ్లండి"
                    )

                    .replace(
                        Regex("^Proceed straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై నేరుగా వెళ్లండి"
                    }

                    .replace(
                        Regex("Follow (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} మార్గాన్ని అనుసరించండి"
                    }

                    .replace(
                        Regex("Cross (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} దాటి వెళ్లండి"
                    }

                    .replace(
                        Regex("Cross the road", RegexOption.IGNORE_CASE),
                        "రోడ్డును దాటండి"
                    )

                    .replace(
                        Regex("Cross the street", RegexOption.IGNORE_CASE),
                        "రోడ్డును దాటండి"
                    )

                    .replace(
                        Regex("At the traffic light", RegexOption.IGNORE_CASE),
                        "ట్రాఫిక్ సిగ్నల్ వద్ద"
                    )

                    .replace(
                        Regex("At the intersection", RegexOption.IGNORE_CASE),
                        "కూడలి వద్ద"
                    )

                    .replace(
                        Regex("At the fork", RegexOption.IGNORE_CASE),
                        "రోడ్డు రెండుగా విడిపోయే చోట"
                    )

                    .replace(
                        Regex("At the next intersection", RegexOption.IGNORE_CASE),
                        "తదుపరి కూడలి వద్ద"
                    )

                    .replace(
                        Regex("At the next traffic light", RegexOption.IGNORE_CASE),
                        "తదుపరి ట్రాఫిక్ సిగ్నల్ వద్ద"
                    )

                    .replace(
                        Regex("Take the next left", RegexOption.IGNORE_CASE),
                        "తదుపరి మలుపులో ఎడమవైపు తిరగండి"
                    )

                    .replace(
                        Regex("Take the next right", RegexOption.IGNORE_CASE),
                        "తదుపరి మలుపులో కుడివైపు తిరగండి"
                    )

                    .replace(
                        Regex("Turn left at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వద్ద ఎడమవైపు తిరగండి"
                    }

                    .replace(
                        Regex("Turn right at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వద్ద కుడివైపు తిరగండి"
                    }

                    .replace(
                        Regex("Turn left toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు ఎడమవైపు తిరగండి"
                    }

                    .replace(
                        Regex("Turn right toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు కుడివైపు తిరగండి"
                    }

                    .replace(
                        Regex("Keep left on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై ఎడమవైపు ఉండండి"
                    }

                    .replace(
                        Regex("Keep right on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} పై కుడివైపు ఉండండి"
                    }

                    .replace(
                        Regex("^Bear left\\.?$", RegexOption.IGNORE_CASE),
                        "ఎడమవైపు వెళ్లండి"
                    )

                    .replace(
                        Regex("^Bear right\\.?$", RegexOption.IGNORE_CASE),
                        "కుడివైపు వెళ్లండి"
                    )

                    .replace(
                        Regex("Turn slightly left", RegexOption.IGNORE_CASE),
                        "కొద్దిగా ఎడమవైపు తిరగండి"
                    )

                    .replace(
                        Regex("Turn slightly right", RegexOption.IGNORE_CASE),
                        "కొద్దిగా కుడివైపు తిరగండి"
                    )

                    .replace(
                        Regex("Continue along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వెంట కొనసాగండి"
                    }

                    .replace(
                        Regex("Continue for (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వరకు కొనసాగండి"
                    }

                    .replace(
                        Regex("Head toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు వెళ్లండి"
                    }

                    .replace(
                        Regex("Head towards (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు వెళ్లండి"
                    }

                    .replace(
                        Regex("Walk along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వెంట నడవండి"
                    }

                    .replace(
                        Regex("Walk past (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} దాటి నడవండి"
                    }

                    .replace(
                        Regex("Pass (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} దాటి వెళ్లండి"
                    }

                    .replace(
                        Regex("Use the sidewalk", RegexOption.IGNORE_CASE),
                        "ఫుట్‌పాత్‌ను ఉపయోగించండి"
                    )

                    .replace(
                        Regex("Take the sidewalk", RegexOption.IGNORE_CASE),
                        "ఫుట్‌పాత్‌పై నడవండి"
                    )

                    .replace(
                        Regex("Take the stairs", RegexOption.IGNORE_CASE),
                        "మెట్లను ఉపయోగించండి"
                    )

                    .replace(
                        Regex("Take the stairs to (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} చేరుకోవడానికి మెట్లను ఉపయోగించండి"
                    }

                    .replace(
                        Regex("Take the path", RegexOption.IGNORE_CASE),
                        "దారిలో వెళ్లండి"
                    )

                    .replace(
                        Regex("Take the path toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} వైపు దారిలో వెళ్లండి"
                    }

                    .replace(
                        Regex("Take the (\\d+)(?:st|nd|rd|th) exit", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}వ నిష్క్రమణలో వెళ్లండి"
                    }

                    .replace(
                        Regex("Take exit (\\d+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}వ నిష్క్రమణలో వెళ్లండి"
                    }
                    .replace("Destination", "గమ్యస్థానం", ignoreCase = true)
                    .replace("Start", "ప్రారంభించండి", ignoreCase = true)
                    .replace("Enter", "ప్రవేశించండి", ignoreCase = true)
                    .replace("Exit", "బయటకు వెళ్లండి", ignoreCase = true)
                    .replace("meters", "మీటర్లు", ignoreCase = true)
                    .replace("meter", "మీటర్", ignoreCase = true)
                    .replace("kilometers", "కిలోమీటర్లు", ignoreCase = true)
                    .replace("kilometer", "కిలోమీటర్", ignoreCase = true)
            }

            "bn-IN" -> {
                instruction
                    .replace(
                        Regex("^Walk north\\.?$", RegexOption.IGNORE_CASE),
                        "উত্তর দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk south\\.?$", RegexOption.IGNORE_CASE),
                        "দক্ষিণ দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk east\\.?$", RegexOption.IGNORE_CASE),
                        "পূর্ব দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk west\\.?$", RegexOption.IGNORE_CASE),
                        "পশ্চিম দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk northeast\\.?$", RegexOption.IGNORE_CASE),
                        "উত্তর-পূর্ব দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk northwest\\.?$", RegexOption.IGNORE_CASE),
                        "উত্তর-পশ্চিম দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk southeast\\.?$", RegexOption.IGNORE_CASE),
                        "দক্ষিণ-পূর্ব দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Walk southwest\\.?$", RegexOption.IGNORE_CASE),
                        "দক্ষিণ-পশ্চিম দিকে এগিয়ে চলুন"
                    )
                    .replace(
                        Regex("^Turn right\\.?$", RegexOption.IGNORE_CASE),
                        "ডানদিকে ঘুরুন"
                    )
                    .replace(
                        Regex("^Turn left\\.?$", RegexOption.IGNORE_CASE),
                        "বামদিকে ঘুরুন"
                    )
                    .replace(
                        Regex("Keep left to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} নিতে বামদিকে থাকুন"
                    }
                    .replace(
                        Regex("Keep right to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} নিতে ডানদিকে থাকুন"
                    }
                    .replace(
                        Regex("Bear left(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} দিকে বামদিকে থাকুন"
                    }
                    .replace(
                        Regex("Bear right(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} দিকে ডানদিকে থাকুন"
                    }
                    .replace(
                        Regex("Turn right onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} রাস্তায় ডানদিকে ঘুরুন"
                    }
                    .replace(
                        Regex("Turn left onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} রাস্তায় বামদিকে ঘুরুন"
                    }
                    .replace("Slight right", "সামান্য ডানদিকে ঘুরুন", ignoreCase = true)
                    .replace("Slight left", "সামান্য বামদিকে ঘুরুন", ignoreCase = true)
                    .replace(
                        Regex("Continue (?:onto|on) (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} রাস্তায় চলতে থাকুন"
                    }
                    .replace(
                        Regex("Keep right to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} রাস্তায় চলতে ডানদিকে থাকুন"
                    }
                    .replace(
                        Regex("Keep left to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} রাস্তায় চলতে বামদিকে থাকুন"
                    }
                    .replace("Make a U-turn", "ইউ-টার্ন নিন", ignoreCase = true)
                    .replace("U-turn", "ইউ-টার্ন", ignoreCase = true)
                    .replace(
                        Regex(
                            "Enter the roundabout and take the (\\d+)(?:st|nd|rd|th) exit",
                            RegexOption.IGNORE_CASE
                        )
                    ) {
                        "গোলচত্বরে প্রবেশ করে ${it.groupValues[1]} নম্বর বেরোনোর পথে বেরিয়ে আসুন"
                    }
                    .replace("Enter the roundabout", "গোলচত্বরে প্রবেশ করুন", ignoreCase = true)
                    .replace("Exit the roundabout", "গোলচত্বর থেকে বেরিয়ে আসুন", ignoreCase = true)
                    .replace("roundabout", "গোলচত্বর", ignoreCase = true)
                    .replace(
                        Regex("Your destination is on the left\\.?$", RegexOption.IGNORE_CASE),
                        "আপনার গন্তব্য বাম দিকে রয়েছে"
                    )
                    .replace(
                        Regex("Your destination is on the right\\.?$", RegexOption.IGNORE_CASE),
                        "আপনার গন্তব্য ডান দিকে রয়েছে"
                    )
                    .replace(
                        Regex("Keep left (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "যেখানে রাস্তা দুই দিকে ভাগ হয়ে যায়, সেখানে বাম দিকে থাকুন"
                    )
                    .replace(
                        Regex("Keep right (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "যেখানে রাস্তা দুই দিকে ভাগ হয়ে যায়, সেখানে ডান দিকে থাকুন"
                    )
                    .replace(
                        Regex(
                            "You have arrived at your destination\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "আপনি আপনার গন্তব্যে পৌঁছে গেছেন।"
                    )
                    .replace(
                        Regex(
                            "Stay straight to take the ramp\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "র‍্যাম্পে যাওয়ার জন্য সোজা চলতে থাকুন।"
                    )
                    .replace(
                        Regex("^Continue straight\\.?$", RegexOption.IGNORE_CASE),
                        "সোজা এগিয়ে চলুন"
                    )

                    .replace(
                        Regex("^Continue straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} দিয়ে সোজা এগিয়ে চলুন"
                    }

                    .replace(
                        Regex("^Continue straight onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} দিয়ে সোজা এগিয়ে চলুন"
                    }

                    .replace(
                        Regex("^Take the ramp\\.?$", RegexOption.IGNORE_CASE),
                        "র‍্যাম্পে উঠুন"
                    )

                    .replace(
                        Regex("Merge onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এ মিশে যান"
                    }

                    .replace(
                        Regex("^Proceed straight\\.?$", RegexOption.IGNORE_CASE),
                        "সোজা এগিয়ে যান"
                    )

                    .replace(
                        Regex("^Proceed straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} দিয়ে সোজা এগিয়ে যান"
                    }

                    .replace(
                        Regex("Follow (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} পথ অনুসরণ করুন"
                    }

                    .replace(
                        Regex("Cross (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} পার হয়ে যান"
                    }

                    .replace(
                        Regex("Cross the road", RegexOption.IGNORE_CASE),
                        "রাস্তা পার হন"
                    )

                    .replace(
                        Regex("Cross the street", RegexOption.IGNORE_CASE),
                        "রাস্তা পার হন"
                    )

                    .replace(
                        Regex("At the traffic light", RegexOption.IGNORE_CASE),
                        "ট্রাফিক সিগন্যালে"
                    )

                    .replace(
                        Regex("At the intersection", RegexOption.IGNORE_CASE),
                        "মোড়ে"
                    )

                    .replace(
                        Regex("At the fork", RegexOption.IGNORE_CASE),
                        "যেখানে রাস্তা দুই ভাগ হয়েছে"
                    )

                    .replace(
                        Regex("At the next intersection", RegexOption.IGNORE_CASE),
                        "পরবর্তী মোড়ে"
                    )

                    .replace(
                        Regex("At the next traffic light", RegexOption.IGNORE_CASE),
                        "পরবর্তী ট্রাফিক সিগন্যালে"
                    )

                    .replace(
                        Regex("Take the next left", RegexOption.IGNORE_CASE),
                        "পরের মোড়ে বাম দিকে ঘুরুন"
                    )

                    .replace(
                        Regex("Take the next right", RegexOption.IGNORE_CASE),
                        "পরের মোড়ে ডান দিকে ঘুরুন"
                    )

                    .replace(
                        Regex("Turn left at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এ বাম দিকে ঘুরুন"
                    }

                    .replace(
                        Regex("Turn right at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এ ডান দিকে ঘুরুন"
                    }

                    .replace(
                        Regex("Turn left toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এর দিকে বাম দিকে ঘুরুন"
                    }

                    .replace(
                        Regex("Turn right toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এর দিকে ডান দিকে ঘুরুন"
                    }

                    .replace(
                        Regex("Keep left on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এ বাম দিকে থাকুন"
                    }

                    .replace(
                        Regex("Keep right on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এ ডান দিকে থাকুন"
                    }

                    .replace(
                        Regex("^Bear left\\.?$", RegexOption.IGNORE_CASE),
                        "বাম দিকে যান"
                    )

                    .replace(
                        Regex("^Bear right\\.?$", RegexOption.IGNORE_CASE),
                        "ডান দিকে যান"
                    )

                    .replace(
                        Regex("Turn slightly left", RegexOption.IGNORE_CASE),
                        "সামান্য বাম দিকে ঘুরুন"
                    )

                    .replace(
                        Regex("Turn slightly right", RegexOption.IGNORE_CASE),
                        "সামান্য ডান দিকে ঘুরুন"
                    )

                    .replace(
                        Regex("Continue along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} বরাবর এগিয়ে চলুন"
                    }

                    .replace(
                        Regex("Continue for (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} পর্যন্ত এগিয়ে চলুন"
                    }

                    .replace(
                        Regex("Head toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এর দিকে এগিয়ে যান"
                    }

                    .replace(
                        Regex("Head towards (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এর দিকে এগিয়ে যান"
                    }

                    .replace(
                        Regex("Walk along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} বরাবর হাঁটুন"
                    }

                    .replace(
                        Regex("Walk past (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} পার হয়ে হাঁটুন"
                    }

                    .replace(
                        Regex("Pass (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} পার হয়ে যান"
                    }

                    .replace(
                        Regex("Use the sidewalk", RegexOption.IGNORE_CASE),
                        "ফুটপাত ব্যবহার করুন"
                    )

                    .replace(
                        Regex("Take the sidewalk", RegexOption.IGNORE_CASE),
                        "ফুটপাতে হাঁটুন"
                    )

                    .replace(
                        Regex("Take the stairs", RegexOption.IGNORE_CASE),
                        "সিঁড়ি ব্যবহার করুন"
                    )

                    .replace(
                        Regex("Take the stairs to (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} পৌঁছানোর জন্য সিঁড়ি ব্যবহার করুন"
                    }

                    .replace(
                        Regex("Take the path", RegexOption.IGNORE_CASE),
                        "পথ দিয়ে যান"
                    )

                    .replace(
                        Regex("Take the path toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]}-এর দিকে পথ দিয়ে যান"
                    }

                    .replace(
                        Regex("Take the (\\d+)(?:st|nd|rd|th) exit", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} নম্বর প্রস্থান দিয়ে বেরিয়ে যান"
                    }

                    .replace(
                        Regex("Take exit (\\d+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} নম্বর প্রস্থান দিয়ে বেরিয়ে যান"
                    }
                    .replace("Destination", "গন্তব্য", ignoreCase = true)
                    .replace("Start", "শুরু করুন", ignoreCase = true)
                    .replace("Enter", "প্রবেশ করুন", ignoreCase = true)
                    .replace("Exit", "বেরিয়ে আসুন", ignoreCase = true)
                    .replace("meters", "মিটার", ignoreCase = true)
                    .replace("meter", "মিটার", ignoreCase = true)
                    .replace("kilometers", "কিলোমিটার", ignoreCase = true)
                    .replace("kilometer", "কিলোমিটার", ignoreCase = true)
            }

            "mr-IN" -> {
                instruction
                    .replace(
                        Regex("^Walk north\\.?$", RegexOption.IGNORE_CASE),
                        "उत्तर दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk south\\.?$", RegexOption.IGNORE_CASE),
                        "दक्षिण दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk east\\.?$", RegexOption.IGNORE_CASE),
                        "पूर्व दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk west\\.?$", RegexOption.IGNORE_CASE),
                        "पश्चिम दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk northeast\\.?$", RegexOption.IGNORE_CASE),
                        "ईशान्य दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk northwest\\.?$", RegexOption.IGNORE_CASE),
                        "वायव्य दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk southeast\\.?$", RegexOption.IGNORE_CASE),
                        "आग्नेय दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Walk southwest\\.?$", RegexOption.IGNORE_CASE),
                        "नैऋत्य दिशेने पुढे चला"
                    )
                    .replace(
                        Regex("^Turn right\\.?$", RegexOption.IGNORE_CASE),
                        "उजवीकडे वळा"
                    )
                    .replace(
                        Regex("^Turn left\\.?$", RegexOption.IGNORE_CASE),
                        "डावीकडे वळा"
                    )
                    .replace(
                        Regex("Keep left to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} घेण्यासाठी डावीकडे रहा"
                    }
                    .replace(
                        Regex("Keep right to take (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} घेण्यासाठी उजवीकडे रहा"
                    }
                    .replace(
                        Regex("Bear left(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने डावीकडे रहा"
                    }
                    .replace(
                        Regex("Bear right(?: onto)? (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने उजवीकडे रहा"
                    }
                    .replace(
                        Regex("Turn right onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर उजवीकडे वळा"
                    }
                    .replace(
                        Regex("Turn left onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर डावीकडे वळा"
                    }
                    .replace("Slight right", "थोडे उजवीकडे वळा", ignoreCase = true)
                    .replace("Slight left", "थोडे डावीकडे वळा", ignoreCase = true)
                    .replace(
                        Regex("Continue (?:onto|on) (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर पुढे जात रहा"
                    }
                    .replace(
                        Regex("Keep right to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर राहण्यासाठी उजवीकडे रहा"
                    }
                    .replace(
                        Regex("Keep left to stay on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर राहण्यासाठी डावीकडे रहा"
                    }
                    .replace("Make a U-turn", "यू-टर्न घ्या", ignoreCase = true)
                    .replace("U-turn", "यू-टर्न", ignoreCase = true)
                    .replace(
                        Regex(
                            "Enter the roundabout and take the (\\d+)(?:st|nd|rd|th) exit",
                            RegexOption.IGNORE_CASE
                        )
                    ) {
                        "गोल चौकात प्रवेश करा आणि ${it.groupValues[1]} व्या बाहेर पडण्याच्या मार्गाने बाहेर पडा"
                    }
                    .replace("Enter the roundabout", "गोल चौकात प्रवेश करा", ignoreCase = true)
                    .replace("Exit the roundabout", "गोल चौकातून बाहेर पडा", ignoreCase = true)
                    .replace("roundabout", "गोल चौक", ignoreCase = true)
                    .replace(
                        Regex("Your destination is on the left\\.?$", RegexOption.IGNORE_CASE),
                        "तुमचे गंतव्य डावीकडे आहे"
                    )
                    .replace(
                        Regex("Your destination is on the right\\.?$", RegexOption.IGNORE_CASE),
                        "तुमचे गंतव्य उजवीकडे आहे"
                    )
                    .replace(
                        Regex("Keep left (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "जिथे रस्ता दोन भागांत विभागतो, तिथे डावीकडे रहा"
                    )
                    .replace(
                        Regex("Keep right (?:at|on) the fork\\.?$", RegexOption.IGNORE_CASE),
                        "जिथे रस्ता दोन भागांत विभागतो, तिथे उजवीकडे रहा"
                    )
                    .replace(
                        Regex(
                            "You have arrived at your destination\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "तुम्ही तुमच्या गंतव्यस्थानी पोहोचला आहात."
                    )
                    .replace(
                        Regex(
                            "Stay straight to take the ramp\\.?$",
                            RegexOption.IGNORE_CASE
                        ),
                        "रॅम्पवर जाण्यासाठी सरळ पुढे जा."
                    )
                    .replace(
                        Regex("^Continue straight\\.?$", RegexOption.IGNORE_CASE),
                        "सरळ पुढे चालत राहा"
                    )

                    .replace(
                        Regex("^Continue straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर सरळ पुढे चालत राहा"
                    }

                    .replace(
                        Regex("^Continue straight onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर सरळ पुढे चालत राहा"
                    }

                    .replace(
                        Regex("^Take the ramp\\.?$", RegexOption.IGNORE_CASE),
                        "रॅम्पवर जा"
                    )

                    .replace(
                        Regex("Merge onto (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर सामील व्हा"
                    }

                    .replace(
                        Regex("^Proceed straight\\.?$", RegexOption.IGNORE_CASE),
                        "सरळ पुढे जा"
                    )

                    .replace(
                        Regex("^Proceed straight on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर सरळ पुढे जा"
                    }

                    .replace(
                        Regex("Follow (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} मार्गाचे अनुसरण करा"
                    }

                    .replace(
                        Regex("Cross (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} ओलांडून जा"
                    }

                    .replace(
                        Regex("Cross the road", RegexOption.IGNORE_CASE),
                        "रस्ता ओलांडा"
                    )

                    .replace(
                        Regex("Cross the street", RegexOption.IGNORE_CASE),
                        "रस्ता ओलांडा"
                    )

                    .replace(
                        Regex("At the traffic light", RegexOption.IGNORE_CASE),
                        "ट्रॅफिक सिग्नलवर"
                    )

                    .replace(
                        Regex("At the intersection", RegexOption.IGNORE_CASE),
                        "चौकात"
                    )

                    .replace(
                        Regex("At the fork", RegexOption.IGNORE_CASE),
                        "रस्ता दोन भागांत विभागतो तिथे"
                    )

                    .replace(
                        Regex("At the next intersection", RegexOption.IGNORE_CASE),
                        "पुढील चौकात"
                    )

                    .replace(
                        Regex("At the next traffic light", RegexOption.IGNORE_CASE),
                        "पुढील ट्रॅफिक सिग्नलवर"
                    )

                    .replace(
                        Regex("Take the next left", RegexOption.IGNORE_CASE),
                        "पुढील वळणावर डावीकडे वळा"
                    )

                    .replace(
                        Regex("Take the next right", RegexOption.IGNORE_CASE),
                        "पुढील वळणावर उजवीकडे वळा"
                    )

                    .replace(
                        Regex("Turn left at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} येथे डावीकडे वळा"
                    }

                    .replace(
                        Regex("Turn right at (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} येथे उजवीकडे वळा"
                    }

                    .replace(
                        Regex("Turn left toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने डावीकडे वळा"
                    }

                    .replace(
                        Regex("Turn right toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने उजवीकडे वळा"
                    }

                    .replace(
                        Regex("Keep left on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर डावीकडे राहा"
                    }

                    .replace(
                        Regex("Keep right on (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वर उजवीकडे राहा"
                    }

                    .replace(
                        Regex("^Bear left\\.?$", RegexOption.IGNORE_CASE),
                        "डावीकडे वळा"
                    )

                    .replace(
                        Regex("^Bear right\\.?$", RegexOption.IGNORE_CASE),
                        "उजवीकडे वळा"
                    )

                    .replace(
                        Regex("Turn slightly left", RegexOption.IGNORE_CASE),
                        "थोडे डावीकडे वळा"
                    )

                    .replace(
                        Regex("Turn slightly right", RegexOption.IGNORE_CASE),
                        "थोडे उजवीकडे वळा"
                    )

                    .replace(
                        Regex("Continue along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वरून पुढे चालत राहा"
                    }

                    .replace(
                        Regex("Continue for (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर्यंत पुढे चालत राहा"
                    }

                    .replace(
                        Regex("Head toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने जा"
                    }

                    .replace(
                        Regex("Head towards (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने जा"
                    }

                    .replace(
                        Regex("Walk along (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} वरून चालत जा"
                    }

                    .replace(
                        Regex("Walk past (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} ओलांडून चालत जा"
                    }

                    .replace(
                        Regex("Pass (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} ओलांडून जा"
                    }

                    .replace(
                        Regex("Use the sidewalk", RegexOption.IGNORE_CASE),
                        "फुटपाथचा वापर करा"
                    )

                    .replace(
                        Regex("Take the sidewalk", RegexOption.IGNORE_CASE),
                        "फुटपाथवरून चला"
                    )

                    .replace(
                        Regex("Take the stairs", RegexOption.IGNORE_CASE),
                        "जिन्याचा वापर करा"
                    )

                    .replace(
                        Regex("Take the stairs to (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} पर्यंत पोहोचण्यासाठी जिन्याचा वापर करा"
                    }

                    .replace(
                        Regex("Take the path", RegexOption.IGNORE_CASE),
                        "रस्त्याने जा"
                    )

                    .replace(
                        Regex("Take the path toward (.+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} च्या दिशेने रस्त्याने जा"
                    }

                    .replace(
                        Regex("Take the (\\d+)(?:st|nd|rd|th) exit", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} व्या बाहेर पडण्याच्या मार्गाने जा"
                    }

                    .replace(
                        Regex("Take exit (\\d+)", RegexOption.IGNORE_CASE)
                    ) {
                        "${it.groupValues[1]} व्या बाहेर पडण्याच्या मार्गाने जा"
                    }
                    .replace("Destination", "गंतव्य", ignoreCase = true)
                    .replace("Start", "सुरू करा", ignoreCase = true)
                    .replace("Enter", "प्रवेश करा", ignoreCase = true)
                    .replace("Exit", "बाहेर पडा", ignoreCase = true)
                    .replace("meters", "मीटर", ignoreCase = true)
                    .replace("meter", "मीटर", ignoreCase = true)
                    .replace("kilometers", "किलोमीटर", ignoreCase = true)
                    .replace("kilometer", "किलोमीटर", ignoreCase = true)
            }

            else -> instruction
        }
    }

    private fun speakRouteInstructions(responseText: String) {

        try {
            val jsonObject = org.json.JSONObject(responseText)
            val trip = jsonObject.getJSONObject("trip")
            val legs = trip.getJSONArray("legs")

            if (legs.length() == 0) {
                return
            }

            val leg = legs.getJSONObject(0)
            val maneuvers = leg.getJSONArray("maneuvers")

            val instructions = mutableListOf<String>()

            for (i in 0 until maneuvers.length()) {

                val maneuver = maneuvers.getJSONObject(i)
                val instruction = maneuver.optString("instruction")

                if (instruction.isNotEmpty()) {
                    instructions.add(
                        translateNavigationInstruction(instruction)
                    )
                }
            }

            if (instructions.isEmpty()) {
                return
            }

            /*
             * Display each instruction when Text-to-Speech
             * actually starts speaking it.
             */
            textToSpeech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {
                        Log.d(
                            "NAVIS_TTS",
                            "onStart called: utteranceId=$utteranceId"
                        )

                        val index =
                            utteranceId
                                ?.removePrefix("ROUTE_")
                                ?.toIntOrNull()

                        if (index != null && index < instructions.size) {

                            interruptedRouteIndex = index
                            interruptedRouteInstruction = instructions[index]

                            Log.d(
                                "NAVIS_ROUTE",
                                "Saved interrupted instruction index=$index: ${instructions[index]}"
                            )

                            runOnUiThread {
                                updateStatus?.invoke(
                                    instructions[index]
                                )
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(
                            "NAVIS_TTS",
                            "onDone called: utteranceId=$utteranceId"
                        )

                        if (utteranceId == "OBSTACLE_WARNING") {

                            Log.d(
                                "NAVIS_ROUTE",
                                "Obstacle warning finished"
                            )

                            interruptedRouteInstruction?.let { instruction ->

                                Log.d(
                                    "NAVIS_ROUTE",
                                    "Resuming route instruction: $instruction"
                                )

                                textToSpeech.speak(
                                    instruction,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "RESUME_ROUTE"
                                )

                                runOnUiThread {
                                    updateStatus?.invoke(instruction)
                                }

                                interruptedRouteInstruction = null
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        // Nothing needed here
                    }
                }
            )

            /*
             * Queue each instruction.
             */
            currentRouteInstructions = instructions
            for (i in instructions.indices) {

                textToSpeech.speak(
                    instructions[i],
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "ROUTE_$i"
                )
            }

        } catch (e: Exception) {

            Log.e(
                "NAVIS_ROUTE",
                "Could not read route instructions",
                e
            )
        }
    }


    // -------------------------
    // TEXT TO SPEECH
    // -------------------------

    private fun speak(text: String) {

        textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "NAVIS"
        )
    }

    private fun announceObstacle(label: String) {
        if (obstacleWarningActive) {
            return
        }

        obstacleWarningActive = true

        Log.d(
            "NAVIS_OBSTACLE",
            "ANNOUNCING OBSTACLE: $label"
        )

        textToSpeech.stop()

        textToSpeech.speak(
            "Warning. Obstacle detected ahead.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "OBSTACLE_WARNING"
        )

        updateStatus?.invoke("Obstacle detected ahead")
        updateResult?.invoke(
            "Warning: $label detected in your walking direction"
        )

        obstacleResumeHandler.postDelayed(
            {
                resumeInterruptedRoute()
            },
            2500L
        )
    }

    private fun resumeInterruptedRoute() {

        if (obstacleResumePending) {
            return
        }

        obstacleResumePending = true

        val index = interruptedRouteIndex

        if (index == null) {
            obstacleResumePending = false
            return
        }

        Log.d(
            "NAVIS_ROUTE",
            "Resuming from route instruction index=$index"
        )

        interruptedRouteInstruction?.let { instruction ->

            textToSpeech.speak(
                instruction,
                TextToSpeech.QUEUE_ADD,
                null,
                "RESUME_ROUTE"
            )

            runOnUiThread {
                updateStatus?.invoke(instruction)
            }
        }

        // Add the remaining route instructions back to the TTS queue
        for (i in (index + 1) until currentRouteInstructions.size) {

            textToSpeech.speak(
                currentRouteInstructions[i],
                TextToSpeech.QUEUE_ADD,
                null,
                "ROUTE_$i"
            )
        }

        interruptedRouteInstruction = null
        interruptedRouteIndex = null

        obstacleResumePending = false
    }
    private fun speakQueued(text: String) {
        textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_ADD,
            null,
            "NAVIS"
        )
    }


    // -------------------------
    // DESTROY
    // -------------------------

    override fun onDestroy() {

        cameraExecutor.shutdown()
        textToSpeech.stop()
        textToSpeech.shutdown()

        super.onDestroy()
    }
}


// ========================================
// NAVIS SCREEN
// ========================================

@Composable
fun NavIsScreen(
    statusText: String,
    recognizedText: String,
    destinationText: String,
    locationText: String,
    onScreenTap: () -> Unit,
    onLocationTap: () -> Unit,
    onLanguageSelected: ((String, String) -> Unit)? = null,
    cameraPreview: @Composable (() -> Unit)? = null
) {

    var languageMenuExpanded by remember {
        mutableStateOf(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                onScreenTap()
            },

        contentAlignment = Alignment.Center
    ) {

        cameraPreview?.invoke()


        // LANGUAGE DROPDOWN - TOP RIGHT
        // LANGUAGE DROPDOWN - TOP RIGHT
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd
        ) {

            Box {

                Text(
                    text = "Language ▾",
                    color = Color.DarkGray,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable {
                        languageMenuExpanded = true
                    }
                )

                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = {
                        languageMenuExpanded = false
                    },
                    containerColor = Color.DarkGray
                ) {

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "English",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "English (UK)",
                                "en-GB"
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "English (India)",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "English (India)",
                                "en-IN"
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "हिन्दी",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "Hindi",
                                "hi-IN"
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "தமிழ்",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "Tamil",
                                "ta-IN"
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "తెలుగు",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "Telugu",
                                "te-IN"
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "বাংলা",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "Bengali",
                                "bn-IN"
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "मराठी",
                                color = Color.White
                            )
                        },
                        onClick = {
                            languageMenuExpanded = false
                            onLanguageSelected?.invoke(
                                "Marathi",
                                "mr-IN"
                            )
                        }
                    )
                }
            }
        }


        // EXISTING NAVIS CONTENT
        Column(

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {

            Text(
                text = "NAVIS",
                color = Color.White,
                fontSize = 40.sp
            )

            Text(
                text = "Voice Navigation App",
                color = Color.White,
                fontSize = 20.sp
            )

            Text(
                text = "\n$statusText",
                color = Color.White,
                fontSize = 22.sp,
                textAlign = TextAlign.Center
            )

            if (recognizedText.isNotEmpty()) {

                Text(
                    text = "\n$recognizedText",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (destinationText.isNotEmpty()) {

                Text(
                    text = "\n$destinationText",
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (locationText.isNotEmpty()) {

                Text(
                    text = "\n$locationText",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = "\nTap anywhere to speak",
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = "\nTap to get location",
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,

                modifier = Modifier.clickable {
                    onLocationTap()
                }
            )
        }
    }
}