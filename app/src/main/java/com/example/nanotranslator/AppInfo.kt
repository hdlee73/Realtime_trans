package com.example.nanotranslator

object AppInfo {
    const val APP_NAME = "Nano 실시간 번역기"
    const val AUTHOR = "이현덕 (hdlee73@gmail.com)"
    const val GITHUB_OWNER = "hdlee73"
    const val GITHUB_REPO = "Realtime_trans"
    const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE

    /** 앱에 내장된 업데이트 정보 (새 버전을 낼 때 맨 위에 추가) */
    val updateNotes: List<Pair<String, List<String>>> = listOf(
        "1.0.0" to listOf(
            "영어·일본어·중국어·스페인어 자동 언어 감지",
            "온디바이스 Gemini Nano 스트리밍 한국어 번역",
            "블루투스 이어폰/스피커 한국어 TTS 출력",
            "GitHub Releases 기반 자동 업데이트",
            "뒤로가기 시 이전 화면으로 이동"
        )
    )
}
