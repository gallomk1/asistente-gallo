package ar.gallo.agenda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Cada unas horas revisa si hay una versión nueva y avisa con una notificación. */
public class UpdateJob extends JobService {
    static final int JOB_ID = 77;
    static final String CHANNEL = "actualizaciones";

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(3L * 60 * 60 * 1000)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { check(this); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    static void check(Context ctx) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(MainActivity.UPDATE_BASE + "version.json?t=" + System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        if (c.getResponseCode() >= 400) return;
        InputStream is = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        JSONObject r = new JSONObject(bo.toString("UTF-8"));
        int remote = r.optInt("versionCode", 0);
        int mine = (int) ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        if (remote <= mine || r.optString("apk", "").isEmpty()) return;
        SharedPreferences p = ctx.getSharedPreferences("agenda", MODE_PRIVATE);
        if (p.getInt("avisadaVersion", 0) >= remote) return;   // ya avisé esta versión
        p.edit().putInt("avisadaVersion", remote).apply();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Avisa cuando hay una versión nueva de la app");
        nm.createNotificationChannel(ch);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 5, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String name = r.optString("name", "una versión nueva");
        String notes = r.optString("notes", "");
        Notification nt = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Hay una actualización: " + name)
                .setContentText(notes.isEmpty() ? "Tocá para abrir la app y actualizar." : notes)
                .setStyle(new Notification.BigTextStyle().bigText((notes.isEmpty() ? "" : notes + "\n") + "Tocá para abrir la app y actualizar."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(9001, nt);
    }
}
