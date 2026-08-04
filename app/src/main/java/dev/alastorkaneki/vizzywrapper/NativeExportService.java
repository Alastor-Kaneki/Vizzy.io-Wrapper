package dev.alastorkaneki.vizzywrapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground export service so long, high-resolution renders survive leaving the activity. */
public final class NativeExportService extends Service {
    public static final String ACTION_EXPORT = "dev.alastorkaneki.vizzywrapper.EXPORT";
    public static final String ACTION_CANCEL = "dev.alastorkaneki.vizzywrapper.CANCEL_EXPORT";
    private static final String EXTRA_PROJECT = "project";
    private static final String EXTRA_OUTPUT = "output";
    private static final String CHANNEL = "native_export";
    private static final int NOTIFICATION_ID = 2206;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;

    public static void start(Context context, ProjectModel project, Uri output) throws Exception {
        Intent intent = new Intent(context, NativeExportService.class)
                .setAction(ACTION_EXPORT)
                .putExtra(EXTRA_PROJECT, project.toJson().toString())
                .putExtra(EXTRA_OUTPUT, output.toString());
        context.startForegroundService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            updateNotification(0, "Cancelling…", true);
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_EXPORT.equals(intent.getAction())) return START_NOT_STICKY;

        cancelled = false;
        Notification initial = buildNotification(0, "Starting native export…", false, false, null);
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            startForeground(NOTIFICATION_ID, initial);
        }

        String projectJson = intent.getStringExtra(EXTRA_PROJECT);
        String outputString = intent.getStringExtra(EXTRA_OUTPUT);
        executor.execute(() -> runExport(projectJson, outputString));
        return START_NOT_STICKY;
    }

    private void runExport(String projectJson, String outputString) {
        Uri output = outputString == null ? null : Uri.parse(outputString);
        try {
            if (projectJson == null || output == null) throw new IllegalArgumentException("Export request is incomplete.");
            ProjectModel project = ProjectModel.fromJson(new JSONObject(projectJson));
            NativeVideoExporter.Result result = NativeVideoExporter.export(this, project, output, new NativeVideoExporter.Listener() {
                @Override public void onProgress(float progress, String stage) {
                    updateNotification(Math.round(progress * 100f), stage, false);
                }

                @Override public boolean isCancelled() {
                    return cancelled;
                }
            });
            showFinished(output, result);
        } catch (NativeVideoExporter.ExportCancelledException cancelledError) {
            showFailure("Export cancelled.");
        } catch (Throwable failure) {
            showFailure(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Native video exports", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress and completion notifications for local Vizzy renders.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(int progress, String text, boolean indeterminate,
                                           boolean finished, Uri output) {
        Intent cancelIntent = new Intent(this, NativeExportService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 2, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(finished ? "Vizzy export complete" : "Exporting Vizzy project")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(!finished)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (!finished) {
            builder.setProgress(100, progress, indeterminate)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                            "Cancel", cancel).build());
        } else if (output != null) {
            Intent openIntent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(output, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent open = PendingIntent.getActivity(this, 3, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(open).setAutoCancel(true);
        }
        return builder.build();
    }

    private void updateNotification(int progress, String text, boolean indeterminate) {
        Notification notification = buildNotification(progress, text, indeterminate, false, null);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private void showFinished(Uri output, NativeVideoExporter.Result result) {
        String text = result.width + "×" + result.height + " • " + result.frameRate + " FPS • "
                + (result.bitrate / 1_000_000) + " Mbps";
        Notification notification = buildNotification(100, text, false, true, output);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID + 1, notification);
    }

    private void showFailure(String message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Vizzy export failed")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID + 2, notification);
    }

    @Override public void onDestroy() {
        cancelled = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
