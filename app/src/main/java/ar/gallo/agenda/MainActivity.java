package ar.gallo.agenda;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.app.PendingIntent;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.speech.RecognizerIntent;
import java.util.ArrayList;
import android.provider.CalendarContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 10;
    private static final String MODEL = "claude-opus-5-5";
    static final String UPDATE_REPO = "gallomk1/asistente-gallo";
    private static final String ACTION_INSTALL_DONE = "ar.gallo.agenda.INSTALL_DONE";

    private WebView web;
    private boolean pendingStart = false;
    private String pendingAction = ListenService.ACTION_RECORD;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        try { UpdateJob.schedule(this); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs().getBoolean("pidioNotif", false)) {
            prefs().edit().putBoolean("pidioNotif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !ACTION_INSTALL_DONE.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            updateMsg("No se pudo instalar la actualización. Probá de nuevo.");
        }
    }

    private void updateMsg(String m) {
        final String js = "window.__updMsg && window.__updMsg(" + JSONObject.quote(m) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private int myVersion() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return 0; }
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
                int code = c.getResponseCode();
        if (code == 404) throw new UserError("Todavía no hay versiones publicadas.");
        if (code >= 400) throw new UserError("No pude consultar las actualizaciones (" + code + ").");
        InputStream is = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    static final String UPDATE_BASE = "https://raw.githubusercontent.com/" + UPDATE_REPO + "/main/update/";

    /** Lee update/version.json del repositorio y dice si hay una versión más nueva. */
    private JSONObject checkUpdate() throws Exception {
        JSONObject r = new JSONObject(httpGet(UPDATE_BASE + "version.json?t=" + System.currentTimeMillis()));
        int remote = r.optInt("versionCode", 0);
        String apk = r.optString("apk", "");
        JSONObject out = new JSONObject();
        out.put("current", myVersion());
        out.put("remote", remote);
        out.put("name", r.optString("name", ""));
        out.put("notes", r.optString("notes", ""));
        out.put("url", apk.isEmpty() ? "" : UPDATE_BASE + apk);
        out.put("available", !apk.isEmpty() && remote > myVersion());
        return out;
    }

    private void downloadAndInstall(String url) throws Exception {
        if (!url.startsWith(UPDATE_BASE) || url.contains("..")) throw new UserError("La dirección de la actualización no es la esperada.");
        File f = new File(getCacheDir(), "update.apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        if (c.getResponseCode() >= 400) throw new UserError("No pude bajar la actualización.");
        try (InputStream is = c.getInputStream(); FileOutputStream fo = new FileOutputStream(f)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
        }
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        int sid = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sid)) {
            try (OutputStream os = session.openWrite("app", 0, f.length()); FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
            }
            Intent done = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_DONE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pIntent = PendingIntent.getActivity(this, 5, done, flags);
            session.commit(pIntent.getIntentSender());
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private SharedPreferences prefs() { return getSharedPreferences("agenda", MODE_PRIVATE); }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay una app para abrir esto", Toast.LENGTH_SHORT).show();
        }
    }

    private void startListeningNow() {
        Intent i = new Intent(this, ListenService.class).setAction(pendingAction);
        startForegroundService(i);
    }

    private void requestStart() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) {
            startListeningNow();
        } else {
            pendingStart = true;
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMS || !pendingStart) return;
        pendingStart = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListeningNow();
        } else {
            ListenService.lastError = "Sin permiso de micrófono no puedo escuchar. Podés darlo en Ajustes > Apps > Asistente Gallo > Permisos.";
        }
    }

    private static final int REQ_DICTAR = 4711;
    private int dictId = 0;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DICTAR) return;
        int id = dictId; dictId = 0;
        if (id == 0) return;
        if (res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) { callback(id, true, r.get(0)); return; }
        }
        callback(id, false, "No escuché nada. Tocá el micrófono y hablá de nuevo.");
    }

    private void callback(final int id, final boolean ok, final String text) {
        final String js = "window.__askDone(" + id + "," + ok + "," + JSONObject.quote(text == null ? "" : text) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /** Llama a la API de Claude con la clave que la persona cargó en la app. */
    static class UserError extends Exception { UserError(String m) { super(m); } }

    private String askClaude(String prompt) throws Exception {
        String key = prefs().getString("apiKey", "");
        if (key.isEmpty()) throw new UserError("Falta tu clave de Claude. Cargala en Ajustes, abajo de todo.");
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("max_tokens", 16000);
        body.put("output_config", new JSONObject().put("effort", "low"));
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", msgs);

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", key);
        c.setRequestProperty("anthropic-version", "2026-10-21");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
        }
        String resp = bo.toString("UTF-8");
        if (code == 401) throw new UserError("La clave de Claude no es válida. Revisala en Ajustes.");
        if (code == 429) throw new UserError("Demasiadas consultas seguidas. Esperá un minuto y probá de nuevo.");
        if (code == 402 || resp.contains("credit balance")) throw new UserError("Tu cuenta de Claude no tiene saldo. Cargá crédito en console.anthropic.com.");
        if (code >= 400) {
            String detail = "";
            try { detail = new JSONObject(resp).getJSONObject("error").optString("message", ""); } catch (Exception ignored) { }
            throw new UserError("Claude respondió con un error (" + code + ")" + (detail.isEmpty() ? "" : ": " + detail));
        }
        JSONObject r = new JSONObject(resp);
        if ("refusal".equals(r.optString("stop_reason"))) throw new UserError("Claude no quiso procesar este texto.");
        JSONArray content = r.getJSONArray("content");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.getJSONObject(i);
            if ("text".equals(b.optString("type"))) out.append(b.optString("text"));
        }
        return out.toString();
    }

    public class Bridge {
        @JavascriptInterface public void startListening() {
            runOnUiThread(() -> { pendingAction = ListenService.ACTION_RECORD; requestStart(); });
        }

        /** Termina de anotar; si la palabra clave está activada, queda esperando. */
        @JavascriptInterface public void stopListening() {
            if (!ListenService.running) return;
            startService(new Intent(MainActivity.this, ListenService.class).setAction(ListenService.ACTION_FINISH));
        }

        @JavascriptInterface public boolean isListening() { return ListenService.running && ListenService.recording; }
        @JavascriptInterface public boolean isStandby() { return ListenService.running && !ListenService.recording; }
        @JavascriptInterface public boolean standbyEnabled() { return ListenService.standbyEnabled(MainActivity.this); }
        @JavascriptInterface public String getKeyword() { return ListenService.keyword(MainActivity.this); }
        @JavascriptInterface public String getEndword() { return ListenService.endword(MainActivity.this); }
        @JavascriptInterface public void setEndword(String k) {
            String v = k == null ? "" : k.trim();
            prefs().edit().putString("endword", v.isEmpty() ? "fin" : v).apply();
            if (ListenService.running) startService(new Intent(MainActivity.this, ListenService.class).setAction(ListenService.ACTION_STANDBY));
        }

        @JavascriptInterface public void setKeyword(String k) {
            String v = k == null ? "" : k.trim();
            if (v.isEmpty()) v = "gallo";
            prefs().edit().putString("keyword", v).apply();
            if (ListenService.running) startService(new Intent(MainActivity.this, ListenService.class).setAction(ListenService.ACTION_STANDBY));
        }

        @JavascriptInterface public void setStandby(boolean on) {
            prefs().edit().putBoolean("standby", on).apply();
            runOnUiThread(() -> {
                if (on) { pendingAction = ListenService.ACTION_STANDBY; requestStart(); }
                else if (ListenService.running) startService(new Intent(MainActivity.this, ListenService.class).setAction(ListenService.ACTION_STOP));
            });
        }

        @JavascriptInterface public int version() { return myVersion(); }

        /** Dictado corto con el cartel de voz de Android (para objetivos). */
        @JavascriptInterface public void dictar(final int id, final String prompt) {
            runOnUiThread(() -> {
                if (dictId != 0) callback(dictId, false, "Se canceló el dictado anterior.");
                dictId = id;
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
                try { startActivityForResult(i, REQ_DICTAR); }
                catch (ActivityNotFoundException e) { dictId = 0; callback(id, false, "Este celular no tiene el dictado de Google. Escribilo con el teclado (el micrófono del teclado también sirve)."); }
            });
        }

        /** Alarma real del reloj del celular: temporizador si falta menos de una hora, alarma si falta más. */
        @JavascriptInterface public boolean alarm(String label, int seconds, int hour, int minute) {
            Intent i;
            if (seconds <= 3600) {
                i = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            } else {
                i = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            }
            try { startActivity(i); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void checkUpdate(final int id) {
            new Thread(() -> {
                try { callback(id, true, MainActivity.this.checkUpdate().toString()); }
                catch (UserError e) { callback(id, false, e.getMessage()); }
                catch (Exception e) { callback(id, false, "No me pude conectar para buscar actualizaciones."); }
            }).start();
        }

        @JavascriptInterface public void installUpdate(final int id, final String url) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
                callback(id, false, "Activá \"Permitir de esta fuente\" para Asistente Gallo, volvé y tocá Actualizar otra vez.");
                return;
            }
            new Thread(() -> {
                try { downloadAndInstall(url); callback(id, true, "ok"); }
                catch (UserError e) { callback(id, false, e.getMessage()); }
                catch (Exception e) { callback(id, false, "No se pudo bajar o instalar la actualización."); }
            }).start();
        }

        @JavascriptInterface public boolean takeProcess() {
            boolean v = prefs().getBoolean("procesar", false);
            if (v) prefs().edit().putBoolean("procesar", false).apply();
            return v;
        }
        @JavascriptInterface public String partial() { return ListenService.partial; }
        @JavascriptInterface public String takeNew() { return ListenService.takePending(MainActivity.this); }

        @JavascriptInterface public String takeError() {
            String e = ListenService.lastError;
            ListenService.lastError = "";
            return e;
        }

        @JavascriptInterface public boolean hasKey() { return !prefs().getString("apiKey", "").isEmpty(); }
        @JavascriptInterface public void setKey(String k) { prefs().edit().putString("apiKey", k == null ? "" : k.trim()).apply(); }

        @JavascriptInterface public void ask(final int id, final String prompt) {
            new Thread(() -> {
                try {
                    callback(id, true, askClaude(prompt));
                } catch (UserError e) {
                    callback(id, false, e.getMessage());
                } catch (Exception e) {
                    callback(id, false, "No me pude conectar con Claude. Revisá que tengas internet.");
                }
            }).start();
        }

        @JavascriptInterface public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Asistente Gallo", text));
        }

        @JavascriptInterface public void open(String url) { runOnUiThread(() -> openExternal(url)); }

        @JavascriptInterface public void whatsapp(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp");
                try { startActivity(i); }
                catch (ActivityNotFoundException e) {
                    Intent any = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(any, "Mandar con"));
                }
            });
        }

        /** Abre el calendario del celular con el evento ya cargado. allDay: inicio/fin en milisegundos. */
        @JavascriptInterface public void calendar(String title, String details, String location, double begin, double end, boolean allDay, boolean hasDate) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.Events.DESCRIPTION, details);
                if (location != null && !location.isEmpty()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
                if (hasDate) {
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, (long) begin)
                     .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, (long) end)
                     .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay);
                }
                try { startActivity(i); }
                catch (ActivityNotFoundException e) { Toast.makeText(MainActivity.this, "No encontré una app de calendario", Toast.LENGTH_SHORT).show(); }
            });
        }
    }
}
