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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private enum class Screen { MAIN, ABOUT }

class MainActivity : ComponentActivity() {

    private lateinit var translator: GeminiNanoTranslator
    private lateinit var languageDetector: AutoLanguageDetector
    private lateinit var audioRouter: AudioRouteController
    private lateinit var speechManager: ContinuousSpeechManager
    private lateinit var updateManager: UpdateManager

    @Volatile
    private var isSpeakerTtsPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        translator = GeminiNanoTranslator()
        languageDetector = AutoLanguageDetector()
        updateManager = UpdateManager(this)

        setContent {
            MaterialTheme {
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

                Surface(modifier = Modifier.fillMaxSize()) {
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
                            lifecycleScope.launch {
                                val fastDetected = languageDetector.detectLanguage(partial, activeLang)
                                detectedActiveLang = fastDetected
                                speechManager.updateAutoPrimaryLanguage(fastDetected)
                            }
                        }
                    },
                    onFinalSegment = { finalSource, audioDetectedLang ->
                        if (!isSpeakerTtsPlaying) {
                            livePartialText = finalSource
                            lifecycleScope.launch {
                                val resolvedLang = audioDetectedLang
                                    ?: languageDetector.detectLanguage(finalSource, detectedActiveLang)

                                detectedActiveLang = resolvedLang
                                speechManager.updateAutoPrimaryLanguage(resolvedLang)

                                var latestKorean = ""
                                try {
                                    translator.translateStreamToKorean(
                                        sourceText = finalSource,
                                        detectedLang = resolvedLang,
                                        onSpeakableChunk = { chunk ->
                                            audioRouter.enqueueKoreanSpeech(chunk)
                                        }
                                    ).collect { partialKorean ->
                                        latestKorean = partialKorean
                                        streamingKoreanText = partialKorean
                                    }
                                } catch (e: Exception) {
                                    modelStatus = "번역 오류: ${e.localizedMessage}"
                                }

                                if (latestKorean.isNotBlank()) {
                                    historyLogs.add(0, Triple(resolvedLang, finalSource, latestKorean))
                                }
                            }
                        }
                    },
                    onLanguageSwitched = { newLang -> detectedActiveLang = newLang },
                    onErrorInfo = {}
                )
            }
            ready = true
            translator.prepareModel { status -> modelStatus = status }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[Manifest.permission.RECORD_AUDIO] == true && ready) {
                isListening = true
                speechManager.startListening(languageSelection, detectedActiveLang)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Gemini Nano 자동 언어 감지 통역기",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenAbout) { Text("정보") }
            }
            Text(
                text = modelStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = languageSelection is LanguageSelection.Auto,
                    onClick = {
                        languageSelection = LanguageSelection.Auto
                        if (isListening) {
                            speechManager.startListening(LanguageSelection.Auto, detectedActiveLang)
                        }
                    },
                    label = { Text("✨ 자동 감지 (영·일·중·서)") }
                )

                SupportedLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = (languageSelection as? LanguageSelection.Manual)?.language == lang,
                        onClick = {
                            val manual = LanguageSelection.Manual(lang)
                            languageSelection = manual
                            detectedActiveLang = lang
                            if (isListening) {
                                speechManager.startListening(manual, lang)
                            }
                        },
                        label = { Text(lang.displayName) }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val modeLabel = if (languageSelection is LanguageSelection.Auto) {
                        "자동 감지됨: ${detectedActiveLang.displayName} (${detectedActiveLang.bcp47Tag})"
                    } else {
                        "수동 고정됨: ${detectedActiveLang.displayName}"
                    }
                    AssistChip(onClick = {}, label = { Text(modeLabel) })
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = livePartialText.ifBlank { "영·일·중·서 아무 언어로 말씀하세요..." },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("실시간 한국어 출력", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = streamingKoreanText.ifBlank { "..." },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Button(
                enabled = ready,
                onClick = {
                    if (isListening) {
                        isListening = false
                        speechManager.stopListening()
                        audioRouter.stopAll()
                    } else {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            isListening = true
                            speechManager.startListening(languageSelection, detectedActiveLang)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isListening) "실시간 통역 중지" else "자동 언어 감지 통역 시작")
            }

            Text("번역 히스토리", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyLogs) { (lang, src, kor) ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "[${lang.displayName}] $src",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = kor,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 뒤로") }
                Text("앱 정보", style = MaterialTheme.typography.headlineSmall)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("앱 이름: ${AppInfo.APP_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text("버전: ${AppInfo.versionName} (${AppInfo.versionCode})", style = MaterialTheme.typography.bodyLarge)
                    Text("만든이: ${AppInfo.AUTHOR}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Button(onClick = { checkRequested++ }, modifier = Modifier.fillMaxWidth()) {
                Text("업데이트 확인")
            }
            Text(
                "앱을 실행할 때마다 GitHub Releases에서 새 버전을 자동으로 확인합니다.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("업데이트 정보", style = MaterialTheme.typography.titleMedium)
            AppInfo.updateNotes.forEach { (version, notes) ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("v$version", style = MaterialTheme.typography.titleSmall)
                        notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
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
        if (::audioRouter.isInitialized) audioRouter.release()
        languageDetector.close()
        translator.close()
    }
}
