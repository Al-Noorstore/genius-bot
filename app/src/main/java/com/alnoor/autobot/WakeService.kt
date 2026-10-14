package com.alnoor.autobot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * v4.8 "HEY BOT" — hamesha sunne wali foreground service (mic notification ke saath).
 *  1) Vosk offline model ho (voice download ...) to wohi use hota hai: bina internet, bina beep.
 *  2) Model na ho to Android SpeechRecognizer loop (internet + Google app chahiye, kuch phones par beep).
 * "Hey Bot <hukm>" ya "Hey Bot" … (ack) … "<hukm>".  Agent ke dauran "stop/ruko", "haan/nahi" bhi sunta hai.
 * Band karna: Settings toggle, "hey bot off", ya notification ka "Band" button.
 */
class WakeService : Service() {

    companion object {
        private const val CH = "autobot_wake"
        private const val NID = 4107
        private const val ACT_STOP = "com.alnoor.autobot.WAKE_STOP"
        @Volatile var running = false

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java)) } catch (_: Throwable) {}
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, WakeService::class.java)) } catch (_: Throwable) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var awaitUntil = 0L
    private var usingVosk = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_STOP) {
            Jarvis.setWake(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NID, n)
        } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            beginListening()
            main.postDelayed(watchdog, 8000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { sr?.destroy() } catch (_: Throwable) {}
        sr = null
        if (usingVosk) SpeechEngine.stop()
        super.onDestroy()
    }

    // ---------------- notification ----------------
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Hey Bot", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setContentTitle("🎙️ Hey Bot sun raha hai")
            .setContentText("Bolo: \"Hey Bot\" phir hukm. Band: yahan 'Band' dabao.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
        @Suppress("DEPRECATION")
        b.addAction(Notification.Action.Builder(0, "Band", stop).build())
        return b.build()
    }

    // ---------------- listening ----------------
    private fun beginListening() {
        if (!running) return
        if (SpeechEngine.ready(this)) {
            usingVosk = true
            Thread {
                val r = try {
                    SpeechEngine.start(this, onPartial = { }, onFinal = { t -> onHeard(t) },
                        onFail = { main.postDelayed({ beginListening() }, 2000) })
                } catch (e: Throwable) { "❌" }
                if (!r.startsWith("🎤") && !r.contains("Already")) { usingVosk = false; main.post { startSystemListen() } }
            }.start()
        } else {
            usingVosk = false
            main.post { startSystemListen() }
        }
    }

    /** Manual mic / error ke baad dobara sunna shuru karne ke liye */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                if (usingVosk && !SpeechEngine.isListening && !TtsBox.busy()) beginListening()
            } catch (_: Throwable) {}
            main.postDelayed(this, 8000)
        }
    }

    private fun startSystemListen() {
        if (!running) return
        if (TtsBox.busy()) { main.postDelayed({ startSystemListen() }, 800); return }
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) { main.postDelayed({ startSystemListen() }, 15000); return }
            try { sr?.destroy() } catch (_: Throwable) {}
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            sr = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onError(e: Int) {
                    val delay = if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_NETWORK) 4000L else 600L
                    main.postDelayed({ startSystemListen() }, delay)
                }
                override fun onResults(res: Bundle?) {
                    val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) onHeard(t)
                    main.postDelayed({ startSystemListen() }, 300)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            r.startListening(i)
        } catch (_: Throwable) { main.postDelayed({ startSystemListen() }, 5000) }
    }

    // ---------------- what was heard ----------------
    private fun onHeard(raw: String) {
        if (!running) return
        if (TtsBox.busy()) return                        // apni hi awaz mat suno
        val low = raw.lowercase().trim()
        if (low.isEmpty()) return

        // agent chal raha ho: bina wake-word ke rokna / haan-nahi
        if (PhoneAgent.running) {
            if (PhoneAgent.isWaiting) {
                if (Regex("\\b(haan|han|yes|yeah|okay|ok|kar do|theek hai)\\b|हाँ|हां").containsMatchIn(low)) { PhoneAgent.reply(true); return }
                if (Regex("\\b(nahi|nahin|no|nope|mat karo)\\b|नहीं|नही").containsMatchIn(low)) { PhoneAgent.reply(false); return }
            }
            if (Regex("\\b(stop|ruko|ruk jao|rok do|cancel)\\b|रुको|रुक जाओ").containsMatchIn(low)) {
                PhoneAgent.stop(); Jarvis.speak(this, "Theek hai, ruk gaya."); return
            }
        }

        val now = System.currentTimeMillis()
        val (found, rest) = Jarvis.matchWake(low, this)
        if (found) {
            AssistantOverlayService.show(this, "Listening…", 9000L)
            if (rest.length >= 3) { deliver(rest) }
            else { awaitUntil = now + 9000; ack() }
            return
        }
        if (now < awaitUntil) { awaitUntil = 0; deliver(low) }
    }

    private fun ack() {
        TtsBox.stopNow()
        if (Jarvis.voiceOn(this)) Jarvis.speak(this, "Yes boss?")
        else try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Throwable) {}
    }

    private fun deliver(cmd: String) {
        if (Jarvis.voiceOn(this)) Jarvis.speak(this, "Okay.")
        // multi-step hukm (aur/phir/likho/bhejo/search...) => PhoneAgent; seedha hukm (call, open...) => normal chat
        val multi = Regex("\\b(aur|phir|then|uske baad|likho|likh do|bhejo|bhej do|send|search|dhundo|type)\\b").containsMatchIn(cmd)
        val already = Regex("^(?:kaam karo|phone agent|phone kaam|phone chalao|autopilot|auto pilot|phone |hey bot |jarvis )").containsMatchIn(cmd)
        val out = if (multi && !already && !cmd.startsWith("call")) "kaam karo $cmd" else cmd
        Jarvis.deliver(this, out)
    }
}
