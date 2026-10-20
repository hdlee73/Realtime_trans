package com.example.nanotranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Screen { MAIN, ABOUT }

private const val MAX_PENDING_SENTENCES = 3
private const val FOREGROUND_WAIT_MS = 10_000L

class MainActivity : ComponentActivity() {

    private lateinit var translator: GeminiNanoTranslator
    private lateinit var languageDetector: AutoLanguageDetector
    private lateinit var audioRouter: AudioRouteController
    private lateinit var speechManager: ContinuousSpeechManager
    private lateinit var micRouter: MicRouteController
    private lateinit var updateManager: UpdateManager

    @Volatile
    private var isSpeakerTtsPlaying = false

    // Gemini Nano(AICore)는 앱이 화면 앞에 있을 때만 호출할 수 있어서, 포커스 상태를 추적합니다.
    private val resumed = MutableStateFlow(false)
    private val windowFocused = MutableStateFlow(false)
    private val inForeground = MutableStateFlow(false)

    private fun updateForeground() {
        inForeground.value = resumed.value && windowFocused.value
    }

    override fun onResume() {
        super.onResume()
        resumed.value = true
        updateForeground()
    }

    override fun onPause() {
        super.onPause()
        resumed.value = false
        updateForeground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused.value = hasFocus
        updateForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        translator = GeminiNanoTranslator()
        languageDetector = AutoLanguageDetector()
        updateManager = UpdateManager(this)

        setContent {
            NanoTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                var lastBackPressed by remember { mutableLongStateOf(0L) }

                // 뒤로가기: 하위 화면이면 이전 화면으로, 첫 화면이면 두 번 눌러야 종료
                BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
                BackHandler(enabled = screen == Screen.MAIN) {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressed < 2000L) {
                        finish()
                    } else {
                        lastBackPressed = now
                        Toast.makeText(this@MainActivity, "한 번 더 누르면 종료됩니다", Toast.LENGTH_SHORT).show()
                    }
                }

                UpdateCheck(manual = false)

                Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                    when (screen) {
                        Screen.MAIN -> TranslatorScreen(onOpenAbout = { screen = Screen.ABOUT })
                        Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.MAIN })
                    }
                }
            }
        }
    }

    @Composable
    private fun TranslatorScreen(onOpenAbout: () -> Unit) {
        var modelStatus by remember { mutableStateOf("Gemini Nano 상태 확인 중...") }
        var languageSelection by remember { mutableStateOf<LanguageSelection>(LanguageSelection.Auto) }
        var detectedActiveLang by remember { mutableStateOf(SupportedLanguage.ENGLISH) }
        var isListening by remember { mutableStateOf(false) }
        var livePartialText by remember { mutableStateOf("") }
        var streamingKoreanText by remember { mutableStateOf("") }
        val historyLogs = remember { mutableStateListOf<Triple<SupportedLanguage, String, String>>() }
        var ready by remember { mutableStateOf(::speechManager.isInitialized) }
        var micPreference by remember { mutableStateOf(MicPreference.AUTO) }
        var askedBluetoothPermission by rememberSaveable { mutableStateOf(false) }
        var micRoute by remember { mutableStateOf(MicRoute()) }
        val currentSelection by rememberUpdatedState(languageSelection)
        val currentLang by rememberUpdatedState(detectedActiveLang)
        val listeningNow by rememberUpdatedState(isListening)

        val chunker = remember { SentenceChunker() }
        val jobs = remember { ArrayDeque<Pair<String, SupportedLanguage>>() }
        val jobSignal = remember { Channel<Unit>(Channel.CONFLATED) }
        fun enqueue(text: String, lang: SupportedLanguage) {
            if (text.isBlank()) return
            jobs.addLast(text to lang)
            // 밀리면 오래된 문장은 건너뛰고 최신 문장부터 처리 (실시간성 우선)
            while (jobs.size > MAX_PENDING_SENTENCES) jobs.removeFirst()
            jobSignal.trySend(Unit)
        }

        // 번역은 한 번에 한 문장씩, 순서대로 처리한다. AICore 는 화면 앞에서만 호출 가능하다.
        LaunchedEffect(Unit) {
            for (signal in jobSignal) {
                while (true) {
                    val (text, lang) = jobs.removeFirstOrNull() ?: break
                    var attempt = 0
                    while (true) {
                        if (!inForeground.value) {
                            modelStatus = "앱이 화면 앞에 있을 때만 번역할 수 있어요"
                            withTimeoutOrNull(FOREGROUND_WAIT_MS) { inForeground.first { it } }
                        }
                        try {
                            var latest = ""
                            translator.translateStreamToKorean(
                                sourceText = text,
                                detectedLang = lang,
                                onSpeakableChunk = { chunk -> audioRouter.enqueueKoreanSpeech(chunk) }
                            ).collect { partialKorean ->
                                latest = partialKorean
                                streamingKoreanText = partialKorean
                            }
                            if (latest.isNotBlank()) historyLogs.add(0, Triple(lang, text, latest))
                            modelStatus = "온디바이스 Gemini Nano 준비 완료"
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            attempt++
                            val backgroundBlocked = e.message.orEmpty().let {
                                it.contains("Background usage", ignoreCase = true) || it.contains("ErrorCode 30")
                            }
                            val maxAttempts = if (backgroundBlocked) 6 else 2
                            if (attempt >= maxAttempts) {
                                modelStatus = "번역 오류: ${e.localizedMessage}"
                                break
                            }
                            modelStatus = "번역을 다시 시도하는 중... ($attempt)"
                            delay(if (backgroundBlocked) 700L * attempt else 400L)
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            if (!::audioRouter.isInitialized) {
                audioRouter = AudioRouteController(this@MainActivity) { speaking ->
                    isSpeakerTtsPlaying = speaking && !audioRouter.isBluetoothOutputConnected()
                }
            }
            if (!::speechManager.isInitialized) {
                speechManager = ContinuousSpeechManager(
                    context = this@MainActivity,
                    onPartialTranscript = { partial, activeLang ->
                        if (!isSpeakerTtsPlaying) {
                            livePartialText = partial
                            // 문장이 끝나는 즉시 번역 대기열에 넣는다 (문장 단위 통역)
                            chunker.onPartial(partial, activeLang).forEach { enqueue(it, activeLang) }
                            lifecycleScope.launch {
                                val fastDetected = languageDetector.detectLanguage(partial, activeLang)
                                detectedActiveLang = fastDetected
                                speechManager.updateAutoPrimaryLanguage(fastDetected)
                            }
                        }
                    },
                    onFinalSegment = { finalSource, audioDetectedLang ->
                        if (isSpeakerTtsPlaying) {
                            chunker.reset()
                        } else {
                            livePartialText = finalSource
                            lifecycleScope.launch {
                                val resolvedLang = audioDetectedLang
                                    ?: languageDetector.detectLanguage(finalSource, detectedActiveLang)
                                detectedActiveLang = resolvedLang
                                speechManager.updateAutoPrimaryLanguage(resolvedLang)
                            }
                            // 아직 보내지 않은 마지막 문장들
                            chunker.onFinal(finalSource, audioDetectedLang ?: detectedActiveLang).forEach {
                                enqueue(it, audioDetectedLang ?: detectedActiveLang)
                            }
                        }
                    },
                    onLanguageSwitched = { newLang -> detectedActiveLang = newLang },
                    onErrorInfo = {}
                )
            }
            if (!::micRouter.isInitialized) {
                micRouter = MicRouteController(
                    context = this@MainActivity,
                    onRouteChanged = { route -> micRoute = route },
                    onNeedRestart = {
                        if (listeningNow) speechManager.startListening(currentSelection, currentLang)
                    }
                )
            }
            micRoute = micRouter.currentRoute()
            ready = true
            translator.prepareModel { status -> modelStatus = status }
        }

        // 통역 중에는 화면이 꺼지거나 앱이 백그라운드로 가지 않도록 화면을 켜 둔다
        LaunchedEffect(isListening) {
            if (isListening) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[Manifest.permission.RECORD_AUDIO] == true && ready) {
                isListening = true
                micRouter.start()
                speechManager.startListening(languageSelection, detectedActiveLang)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Hero(
                title = AppInfo.APP_NAME,
                subtitle = "영·일·중·서 → 한국어 · 온디바이스 Gemini Nano",
                trailing = { HeroAction("정보", onOpenAbout) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillChip(
                    label = "자동 감지",
                    selected = languageSelection is LanguageSelection.Auto,
                    onClick = {
                        languageSelection = LanguageSelection.Auto
                        if (isListening) {
                            speechManager.startListening(LanguageSelection.Auto, detectedActiveLang)
                        }
                    }
                )
                SupportedLanguage.entries.forEach { lang ->
                    PillChip(
                        label = lang.displayName,
                        selected = (languageSelection as? LanguageSelection.Manual)?.language == lang,
                        onClick = {
                            val manual = LanguageSelection.Manual(lang)
                            languageSelection = manual
                            detectedActiveLang = lang
                            if (isListening) {
                                speechManager.startListening(manual, lang)
                            }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("마이크", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                PillChip(
                    label = "자동 (블루투스 우선)",
                    selected = micPreference == MicPreference.AUTO,
                    onClick = {
                        micPreference = MicPreference.AUTO
                        micRouter.setPreference(MicPreference.AUTO)
                    }
                )
                PillChip(
                    label = "휴대폰 마이크",
                    selected = micPreference == MicPreference.PHONE,
                    onClick = {
                        micPreference = MicPreference.PHONE
                        micRouter.setPreference(MicPreference.PHONE)
                    }
                )
            }

            SoftCard {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(if (isListening) "듣는 중" else "대기 중", active = isListening)
                    StatusPill(
                        when {
                            micRoute.usingBluetooth -> "블루투스 마이크" + (micRoute.bluetoothName?.let { ": $it" } ?: "")
                            micRoute.permissionMissing -> "휴대폰 마이크 (블루투스 권한 필요)"
                            micRoute.bluetoothAvailable -> "휴대폰 마이크 (수동 선택)"
                            else -> "휴대폰 마이크 (헤드셋 없음)"
                        }
                    )
                    StatusPill(
                        if (languageSelection is LanguageSelection.Auto) "자동 감지: ${detectedActiveLang.displayName}"
                        else "고정: ${detectedActiveLang.displayName}"
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = livePartialText.ifBlank { "영·일·중·서 아무 언어로 말씀하세요" },
                    color = if (livePartialText.isBlank()) Muted else Ink,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Line)
                Text("한국어", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = streamingKoreanText.ifBlank { "번역 결과가 여기에 표시됩니다" },
                    color = if (streamingKoreanText.isBlank()) Muted else Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp
                )
            }

            Text(
                text = modelStatus,
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            BigButton(
                text = if (isListening) "통역 중지" else "통역 시작",
                enabled = ready,
                danger = isListening,
                onClick = {
                    if (isListening) {
                        isListening = false
                        speechManager.stopListening()
                        chunker.reset()
                        jobs.clear()
                        micRouter.stop()
                        audioRouter.stopAll()
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        val needsBt = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED && !askedBluetoothPermission
                        if (hasMic && !needsBt) {
                            isListening = true
                            micRouter.start()
                            speechManager.startListening(languageSelection, detectedActiveLang)
                        } else {
                            askedBluetoothPermission = true
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            )
                        }
                    }
                }
            )

            Text(
                "번역 기록",
                color = Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            if (historyLogs.isEmpty()) {
                Text(
                    "아직 번역한 문장이 없어요",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyLogs) { (lang, src, kor) ->
                    SoftCard {
                        Text(lang.displayName, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(src, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(kor, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun AboutScreen(onBack: () -> Unit) {
        var checkRequested by remember { mutableIntStateOf(0) }
        if (checkRequested > 0) {
            key(checkRequested) { UpdateCheck(manual = true) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Hero(
                title = "앱 정보",
                subtitle = "${AppInfo.APP_NAME} v${AppInfo.versionName}",
                trailing = { HeroAction("← 뒤로", onBack) }
            )
            SoftCard {
                Text("앱 이름", color = Muted, fontSize = 12.sp)
                Text(AppInfo.APP_NAME, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Text("버전", color = Muted, fontSize = 12.sp)
                Text("${AppInfo.versionName} (${AppInfo.versionCode})", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Text("만든이", color = Muted, fontSize = 12.sp)
                Text(AppInfo.AUTHOR, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            BigButton(text = "업데이트 확인", onClick = { checkRequested++ })
            Text(
                "앱을 실행할 때마다 GitHub Releases에서 새 버전을 자동으로 확인합니다.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                "업데이트 정보",
                color = Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            AppInfo.updateNotes.forEach { (version, notes) ->
                SoftCard {
                    Text("v$version", color = Teal, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    notes.forEach { Text("• $it", color = Ink, fontSize = 14.sp, lineHeight = 21.sp) }
                }
            }
        }
    }

    /** 최신 릴리스를 확인해 새 버전이 있으면 다이얼로그로 업데이트를 제안합니다. */
    @Composable
    private fun UpdateCheck(manual: Boolean) {
        var release by remember { mutableStateOf<ReleaseInfo?>(null) }
        var progress by remember { mutableStateOf<Int?>(null) }
        var dismissed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            val latest = try {
                updateManager.fetchLatestRelease()
            } catch (_: Exception) {
                null
            }
            if (latest != null && updateManager.isNewer(latest.versionName)) {
                release = latest
            } else if (manual) {
                Toast.makeText(
                    this@MainActivity,
                    if (latest == null) "업데이트 정보를 가져오지 못했습니다" else "최신 버전입니다 (v${AppInfo.versionName})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val info = release ?: return
        if (dismissed) return

        AlertDialog(
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { if (progress == null) dismissed = true },
            title = { Text("새 버전 v${info.versionName}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("현재 버전: v${AppInfo.versionName}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(info.notes.ifBlank { "변경 사항이 없습니다." })
                    progress?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("다운로드 중... $it%")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = progress == null,
                    onClick = {
                        val apkUrl = info.apkUrl
                        if (apkUrl == null) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)))
                            dismissed = true
                        } else if (!updateManager.canInstallPackages()) {
                            Toast.makeText(
                                this@MainActivity,
                                "이 앱의 '알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요",
                                Toast.LENGTH_LONG
                            ).show()
                            updateManager.openInstallPermissionSettings()
                        } else {
                            progress = 0
                            scope.launch {
                                try {
                                    val apk = updateManager.downloadApk(apkUrl) { p -> progress = p }
                                    updateManager.installApk(apk)
                                    dismissed = true
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "다운로드 실패: ${e.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    progress = null
                                }
                            }
                        }
                    }
                ) { Text("업데이트") }
            },
            dismissButton = {
                TextButton(enabled = progress == null, onClick = { dismissed = true }) { Text("나중에") }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechManager.isInitialized) speechManager.stopListening()
        if (::micRouter.isInitialized) micRouter.release()
        if (::audioRouter.isInitialized) audioRouter.release()
        languageDetector.close()
        translator.close()
    }
}
