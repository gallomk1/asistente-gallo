package ar.gallo.agenda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Servicio en primer plano que escucha aunque la pantalla esté apagada.
 * Dos modos:
 *  - "espera": escucha sin guardar nada, solo busca la palabra clave (por defecto "gallo").
 *  - "anotando": guarda todo lo que se dice, hasta que se toca "Listo" o se dice "<clave> listo".
 */
public class ListenService extends Service {
    public static final String ACTION_STOP = "ar.gallo.agenda.STOP";          // apaga todo
    public static final String ACTION_RECORD = "ar.gallo.agenda.RECORD";      // empezar a anotar ya
    public static final String ACTION_FINISH = "ar.gallo.agenda.FINISH";      // terminar de anotar (vuelve a espera si está activada)
    public static final String ACTION_STANDBY = "ar.gallo.agenda.STANDBY";    // quedar esperando la palabra clave
    private static final String CHANNEL = "escucha";
    private static final int NOTIF_ID = 7;

    static volatile boolean running = false;
    static volatile boolean recording = false;
    static volatile String partial = "";
    static volatile String lastError = "";

    private SpeechRecognizer recognizer;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private boolean mutedSystem = false;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("agenda", MODE_PRIVATE); }
    static String keyword(Context c) { return prefs(c).getString("keyword", "gallo"); }
    static boolean standbyEnabled(Context c) { return prefs(c).getBoolean("standby", false); }

    static synchronized void appendPending(Context c, String text) {
        SharedPreferences p = prefs(c);
        String cur = p.getString("pending", "");
        p.edit().putString("pending", cur.isEmpty() ? text : cur + " " + text).apply();
    }

    static synchronized String takePending(Context c) {
        SharedPreferences p = prefs(c);
        String cur = p.getString("pending", "");
        if (!cur.isEmpty()) p.edit().putString("pending", "").apply();
        return cur;
    }

    /** Marca que hay que procesar lo anotado la próxima vez que se abra la app. */
    static void flagProcess(Context c, boolean v) { prefs(c).edit().putBoolean("procesar", v).apply(); }

    static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Escucha", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Aviso visible mientras la app escucha");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RECORD : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs(this).edit().putBoolean("standby", false).apply();
            if (recording) flagProcess(this, true);
            recording = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_FINISH.equals(action)) {
            if (recording) flagProcess(this, true);
            recording = false;
            if (!standbyEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        } else if (ACTION_RECORD.equals(action)) {
            recording = true;
        } else if (ACTION_STANDBY.equals(action)) {
            // queda en espera; si ya estaba anotando, sigue anotando
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (!running) {
            running = true;
            lastError = "";
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agenda:escucha");
            wakeLock.acquire(12 * 60 * 60 * 1000L);
            muteBeeps(true);
            startRecognizer();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ListenService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        String kw = keyword(this);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setOngoing(true)
                .setContentIntent(openPi);
        if (recording) {
            Intent fin = new Intent(this, ListenService.class).setAction(ACTION_FINISH);
            PendingIntent finPi = PendingIntent.getService(this, 2, fin, PendingIntent.FLAG_IMMUTABLE);
            b.setContentTitle("Anotando lo que se habla")
             .setContentText("Decí \"" + kw + " listo\" o tocá Listo para terminar")
             .addAction(new Notification.Action.Builder(null, "Listo", finPi).build());
        } else {
            Intent rec = new Intent(this, ListenService.class).setAction(ACTION_RECORD);
            PendingIntent recPi = PendingIntent.getService(this, 3, rec, PendingIntent.FLAG_IMMUTABLE);
            b.setContentTitle("Esperando la palabra \"" + kw + "\"")
             .setContentText("Decí \"" + kw + "\" para empezar a anotar")
             .addAction(new Notification.Action.Builder(null, "Anotar ahora", recPi).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Apagar", stopPi).build());
        return b.build();
    }

    private void refreshNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
    }

    /** El reconocedor suena en cada reinicio: silencio solo los sonidos del sistema mientras escucha. */
    private void muteBeeps(boolean mute) {
        try {
            if (mute && !mutedSystem) {
                audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
                audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
                mutedSystem = true;
            } else if (!mute && mutedSystem) {
                audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
                audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
                mutedSystem = false;
            }
        } catch (Exception ignored) { }
    }

    private Intent recognizerIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return i;
    }

    /** Procesa una frase reconocida según el modo, buscando la palabra clave. */
    private void handlePhrase(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return;
        String kw = norm(keyword(this));
        String n = norm(text);
        int at = kw.isEmpty() ? -1 : (" " + n + " ").indexOf(" " + kw + " ");
        if (!recording) {
            if (at < 0) return; // en espera no se guarda nada
            recording = true;
            String after = n.substring(Math.min(n.length(), at + kw.length())).trim();
            if (after.startsWith("anota que ")) after = after.substring(10);
            if (!after.isEmpty() && !isFinish(after)) appendPending(this, after + ".");
            refreshNotification();
            return;
        }
        // anotando
        if (at >= 0) {
            String after = n.substring(Math.min(n.length(), at + kw.length())).trim();
            if (isFinish(after)) {
                String before = n.substring(0, Math.max(0, at)).trim();
                if (!before.isEmpty()) appendPending(this, before + ".");
                recording = false;
                flagProcess(this, true);
                if (!standbyEnabled(this)) { stopSelf(); return; }
                refreshNotification();
                return;
            }
        }
        appendPending(this, text + ".");
    }

    private static boolean isFinish(String after) {
        return after.startsWith("listo") || after.startsWith("termina") || after.startsWith("chau") || after.startsWith("basta") || after.startsWith("fin");
    }

    private void startRecognizer() {
        if (!running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastError = "Este celular no tiene reconocimiento de voz. Instalá o actualizá la app de Google.";
            stopSelf();
            return;
        }
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (recording && r != null && !r.isEmpty()) partial = r.get(0);
            }

            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                partial = "";
                if (r != null && !r.isEmpty()) handlePhrase(r.get(0));
                restart(150);
            }

            @Override public void onError(int error) {
                partial = "";
                switch (error) {
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        lastError = "Falta el permiso del micrófono.";
                        stopSelf();
                        return;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        restart(100);
                        return;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER:
                        restart(2000);
                        return;
                    default:
                        restart(800);
                }
            }
        });
        try {
            recognizer.startListening(recognizerIntent());
        } catch (Exception e) {
            restart(1500);
        }
    }

    private void restart(long delayMs) {
        if (!running) return;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::startRecognizer, delayMs);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        recording = false;
        partial = "";
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        muteBeeps(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
