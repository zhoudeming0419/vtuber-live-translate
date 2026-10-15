package com.xyq.livetranslate.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.xyq.livetranslate.AppStrings
import com.xyq.livetranslate.CaptureService
import com.xyq.livetranslate.LiveProvider
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SessionPromptContext
import com.xyq.livetranslate.SettingsStore
import com.xyq.livetranslate.StatusBus
import com.xyq.livetranslate.TranslationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SessionCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanGlobalState() {
        StatusBus.serviceRunning = false
        StatusBus.captureMode = ""
        StatusBus.reset()
    }

    @Before
    fun clearSettings() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun restoreSettings() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---------- 启动前的配置拦截 ----------

    @Test
    fun startWithoutConfigNamesTheSelectedProviderAndSendsToSettings() {
        val host = FakeSessionHost()
        val coordinator = coordinator(host)

        // 默认服务商是 Gemini，未配置时提示应指向 Gemini
        coordinator.onModeToggle(StatusBus.MODE_MIC)

        val message = host.messages.single()
        assertTrue("提示应带上服务商名字：$message", message.contains(geminiLabel()))
        assertEquals(1, host.translationSettingsOpened)
        assertTrue(host.startedForegroundServices.isEmpty())
        assertNull(coordinator.pendingSnapshotForTest())
    }

    @Test
    fun videoStartWithQwenSelectedNeverAsksForGeminiKey() {
        // 选中的是千问，拦截提示必须跟着服务商走：以前这里一律报「请填 Gemini Key」，
        // 把已经配好千问的人挡在门外，还指向了错的设置项。
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        val host = FakeSessionHost()
        val coordinator = coordinator(host)

        coordinator.onModeToggle(StatusBus.MODE_VIDEO)

        val message = host.messages.single()
        assertTrue("提示应指向千问：$message", message.contains(qwenLabel()))
        assertFalse("不应再提 Gemini：$message", message.contains(geminiLabel()))
        assertEquals(1, host.translationSettingsOpened)
        assertTrue(host.startedForegroundServices.isEmpty())
    }

    @Test
    fun geminiConfiguredStillBlocksOnItsOwnMissingKeyOnly() {
        // 千问配好了但当前选的是 Gemini 且没填 Key：仍然要拦，且提示指向 Gemini
        SettingsStore.saveLiveQwenApiKey(context, "sk-demo-key")
        SettingsStore.saveLiveQwenBaseUrl(context, "wss://ws-demo.cn-beijing.maas.aliyuncs.com")
        val host = FakeSessionHost()
        val coordinator = coordinator(host)

        coordinator.onModeToggle(StatusBus.MODE_MIC)

        val message = host.messages.single()
        assertTrue("提示应指向 Gemini：$message", message.contains(geminiLabel()))
        assertFalse("不应提千问：$message", message.contains(qwenLabel()))
        assertEquals(1, host.translationSettingsOpened)
    }

    /** 断言用界面语言下的实际文案，避免写死中文在英文测试环境里误报。 */
    private fun geminiLabel(): String = AppStrings.get(R.string.rt_live_provider_gemini)

    private fun qwenLabel(): String = AppStrings.get(R.string.rt_live_provider_qwen)

    @Test
    fun api33MissingPermissionsRequestsAudioAndNotification() {
        val host = FakeSessionHost()
        val coordinator = coordinator(host)

        coordinator.beginPreparedSessionForTest(snapshot())

        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            host.requestedPermissions.single().toList(),
        )
        assertEquals(PendingSessionStage.WAITING_PERMISSION, coordinator.pendingSnapshotForTest()?.stage)
        assertTrue(host.startedForegroundServices.isEmpty())
    }

    @Test
    fun audioPermissionResultReusesFrozenSnapshotAfterInputsChange() {
        val host = FakeSessionHost()
        val contexts = FakeSessionContextAccess("context-before-permission")
        val coordinator = coordinator(host, contexts)
        val frozen = snapshot(context = contexts.value)

        coordinator.beginPreparedSessionForTest(frozen)
        contexts.value = "context-after-permission"
        host.granted += Manifest.permission.RECORD_AUDIO
        host.granted += Manifest.permission.POST_NOTIFICATIONS
        coordinator.onAudioPermissionResult()

        assertCaptureIntentMatches(frozen, host.startedForegroundServices.single())
        assertNull(coordinator.pendingSnapshotForTest())
    }

    @Test
    fun permissionInFlightRestoresAndStartsAndConsumesOnlyOnce() {
        val firstHost = FakeSessionHost()
        val firstContexts = FakeSessionContextAccess("frozen-before-recreate")
        val first = coordinator(firstHost, firstContexts)
        val frozen = snapshot(context = firstContexts.value)
        first.beginPreparedSessionForTest(frozen)
        val state = android.os.Bundle().also(first::saveState)

        val restoredHost = FakeSessionHost().apply {
            granted += Manifest.permission.RECORD_AUDIO
            granted += Manifest.permission.POST_NOTIFICATIONS
        }
        val restoredContexts = FakeSessionContextAccess("changed-after-recreate")
        val restored = coordinator(restoredHost, restoredContexts)
        restored.restoreState(state)

        assertEquals(PendingSessionStage.WAITING_PERMISSION, restored.pendingSnapshotForTest()?.stage)
        restored.onAudioPermissionResult()
        restored.onAudioPermissionResult()

        assertCaptureIntentMatches(frozen, restoredHost.startedForegroundServices.single())
        assertNull(restored.pendingSnapshotForTest())
    }

    @Test
    fun projectionCancellationDoesNotStartOrConsumeAndKeepsFrozenSnapshot() {
        val host = FakeSessionHost().apply {
            granted += Manifest.permission.RECORD_AUDIO
            overlayAllowed = true
        }
        val contexts = FakeSessionContextAccess("video-frozen")
        val coordinator = coordinator(host, contexts)
        val frozen = snapshot(
            captureMode = StatusBus.MODE_VIDEO,
            context = contexts.value,
            title = "video-title",
        )

        coordinator.beginPreparedSessionForTest(frozen)
        assertEquals(1, host.projectionLaunches.size)
        coordinator.onProjectionResult(Activity.RESULT_CANCELED, null)

        assertTrue(host.startedForegroundServices.isEmpty())
        assertEquals(
            frozen.copy(stage = PendingSessionStage.READY),
            coordinator.pendingSnapshotForTest(),
        )
    }

    @Test
    fun projectionInFlightRestoresThenSuccessStartsAndConsumesOnlyOnce() {
        val firstHost = FakeSessionHost().apply {
            granted += Manifest.permission.RECORD_AUDIO
            overlayAllowed = true
        }
        val frozen = snapshot(
            captureMode = StatusBus.MODE_VIDEO,
            context = "video-before-recreate",
            title = "video-title",
        )
        val first = coordinator(firstHost)
        first.beginPreparedSessionForTest(frozen)
        val state = android.os.Bundle().also(first::saveState)

        val restoredHost = FakeSessionHost().apply {
            granted += Manifest.permission.RECORD_AUDIO
            overlayAllowed = true
        }
        val restoredContexts = FakeSessionContextAccess("video-after-recreate")
        val restored = coordinator(restoredHost, restoredContexts)
        restored.restoreState(state)
        assertEquals(PendingSessionStage.WAITING_PROJECTION, restored.pendingSnapshotForTest()?.stage)

        val projectionData = Intent("projection-result")
        restored.onProjectionResult(Activity.RESULT_OK, projectionData)
        restored.onProjectionResult(Activity.RESULT_OK, projectionData)

        val started = restoredHost.startedForegroundServices.single()
        assertCaptureIntentMatches(frozen, started, includeProjection = true)
        assertEquals(Activity.RESULT_OK, started.getIntExtra(CaptureService.EXTRA_RESULT_CODE, -1))
        assertNotNull(started.getParcelableExtra<Intent>(CaptureService.EXTRA_RESULT_DATA))
        assertNull(restored.pendingSnapshotForTest())
    }

    @Test
    fun captureIntentCarriesAllFrozenContentAndNoKeyOrToken() {
        val host = FakeSessionHost().apply {
            granted += Manifest.permission.RECORD_AUDIO
        }
        val frozen = snapshot()
        val coordinator = coordinator(host)

        coordinator.beginPreparedSessionForTest(frozen)

        assertCaptureIntentMatches(frozen, host.startedForegroundServices.single())
    }


    @Test
    fun waitingOverlayResumesWhenPermissionGrantedOnHostResume() {
        val host = FakeSessionHost().apply {
            granted += Manifest.permission.RECORD_AUDIO
            overlayAllowed = false
        }
        val coordinator = coordinator(host)
        val frozen = snapshot(
            captureMode = StatusBus.MODE_VIDEO,
            context = "overlay-wait",
            title = "video-title",
        )
        coordinator.beginPreparedSessionForTest(frozen)
        assertEquals(1, host.overlaySettingsOpened)
        assertEquals(PendingSessionStage.WAITING_OVERLAY, coordinator.pendingSnapshotForTest()?.stage)
        assertTrue(host.projectionLaunches.isEmpty())

        host.overlayAllowed = true
        coordinator.onHostResume()

        assertEquals(1, host.projectionLaunches.size)
        assertEquals(PendingSessionStage.WAITING_PROJECTION, coordinator.pendingSnapshotForTest()?.stage)
    }

    private fun coordinator(
        host: FakeSessionHost,
        contexts: FakeSessionContextAccess = FakeSessionContextAccess("unused-current-context"),
    ) = SessionCoordinator(
        context = context,
        persistDraftInputs = {},
        sessionContextAccess = contexts,
        host = host,
    )

    private fun snapshot(
        captureMode: String = StatusBus.MODE_MIC,
        context: String = "frozen-context",
        title: String = "frozen-title",
    ) = PendingSessionSnapshot(
        captureMode = captureMode,
        prompt = "frozen-prompt",
        sourceLanguageCode = "ja",
        targetLanguageCode = "zh",
        scenePresetId = "frozen-scene-id",
        sceneLabel = "frozen-scene-label",
        sessionTitle = title,
        sessionContext = context,
    )

    private fun assertCaptureIntentMatches(
        snapshot: PendingSessionSnapshot,
        intent: Intent,
        includeProjection: Boolean = false,
    ) {
        assertEquals(CaptureService.ACTION_START, intent.action)
        assertEquals(snapshot.captureMode, intent.getStringExtra(CaptureService.EXTRA_MODE))
        assertEquals(snapshot.prompt, intent.getStringExtra(CaptureService.EXTRA_SESSION_PROMPT))
        assertEquals(snapshot.sourceLanguageCode, intent.getStringExtra(CaptureService.EXTRA_SOURCE_LANGUAGE))
        assertEquals(snapshot.targetLanguageCode, intent.getStringExtra(CaptureService.EXTRA_TARGET_LANGUAGE))
        assertEquals(snapshot.scenePresetId, intent.getStringExtra(CaptureService.EXTRA_SCENE_PRESET))
        assertEquals(snapshot.sceneLabel, intent.getStringExtra(CaptureService.EXTRA_SCENE_LABEL))
        assertEquals(snapshot.sessionTitle, intent.getStringExtra(CaptureService.EXTRA_SESSION_TITLE))
        assertEquals(snapshot.sessionContext, intent.getStringExtra(CaptureService.EXTRA_SESSION_CONTEXT))
        val contentKeys = setOf(
            CaptureService.EXTRA_MODE,
            CaptureService.EXTRA_SESSION_PROMPT,
            CaptureService.EXTRA_SOURCE_LANGUAGE,
            CaptureService.EXTRA_TARGET_LANGUAGE,
            CaptureService.EXTRA_SCENE_PRESET,
            CaptureService.EXTRA_SCENE_LABEL,
            CaptureService.EXTRA_SESSION_TITLE,
            CaptureService.EXTRA_SESSION_CONTEXT,
        )
        val expectedKeys = if (includeProjection) {
            contentKeys + CaptureService.EXTRA_RESULT_CODE + CaptureService.EXTRA_RESULT_DATA
        } else {
            contentKeys
        }
        val extraKeys = requireNotNull(intent.extras).keySet()
        assertEquals(expectedKeys, extraKeys)
        assertFalse(
            extraKeys.any { key ->
                key.contains("apiKey", ignoreCase = true) || key.contains("token", ignoreCase = true)
            },
        )
    }

    private class FakeSessionContextAccess(initial: String) : SessionContextAccess {
        var value: String = initial

        override fun current(mode: TranslationMode): SessionPromptContext =
            SessionPromptContext(manualContext = value)

    }

    private class FakeSessionHost : SessionHost {
        val granted = mutableSetOf<String>()
        val requestedPermissions = mutableListOf<Array<String>>()
        val projectionLaunches = mutableListOf<Intent>()
        val startedForegroundServices = mutableListOf<Intent>()
        val startedServices = mutableListOf<Intent>()
        val messages = mutableListOf<String>()
        var overlayAllowed = false
        var overlaySettingsOpened = 0
        var translationSettingsOpened = 0

        override fun checkPermission(permission: String): Boolean = permission in granted

        override fun requestPermissions(permissions: Array<String>) {
            requestedPermissions += permissions
        }

        override fun canDrawOverlays(): Boolean = overlayAllowed

        override fun openOverlaySettings() {
            overlaySettingsOpened += 1
        }

        override fun launchProjection(intent: Intent) {
            projectionLaunches += intent
        }

        override fun startForegroundService(intent: Intent) {
            startedForegroundServices += intent
        }

        override fun startService(intent: Intent) {
            startedServices += intent
        }

        override fun openTranslationSettings() {
            translationSettingsOpened += 1
        }

        override fun toast(message: String) {
            messages += message
        }
    }
}
