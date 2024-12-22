package me.mamiiblt.instafel.updater.update;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import me.mamiiblt.instafel.updater.utils.AppPreferences;
import me.mamiiblt.instafel.updater.utils.LocalizationUtils;
import me.mamiiblt.instafel.updater.utils.LogUtils;
import me.mamiiblt.instafel.updater.utils.Utils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateWork extends Worker {

    private static final String CHANNEL_ID = "ifl_updater_channel";
    private static final int NOTIFICATION_ID = 1;
    private AppPreferences appPreferences;
    private Context ctx;
    private double currentDownloadedSizeMegabyte;
    private long fileSize;
    private String formattedFileSize;
    private DecimalFormat df;
    private NotificationCompat.Builder notificationBuilder;
    private int notificationId = 105;
    private String uVersion;
    private LogUtils logUtils;
    private NotificationManager notificationManager;
    private LocalizationUtils localizationUtils;

    public UpdateWork(Context ctx, WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        logUtils = new LogUtils(getApplicationContext());
        ctx = getApplicationContext();
        logUtils.w("");
        logUtils.w("Work is running.");
        notificationBuilder = new NotificationCompat.Builder(ctx, CHANNEL_ID);
        notificationManager = ctx.getSystemService(NotificationManager.class);
        localizationUtils = new LocalizationUtils(getApplicationContext());

        // Update locale

        localizationUtils.updateAppLanguage();

        // Get arch and type from SharedPreferences

        String arch; String type;
        String prefArch = preferences.getString("checker_arch", "non");
        String prefType = preferences.getString("checker_type", "non");
        if (prefArch.equals("arm64-v8a (64-bit)")) {
            arch = "arm64";
        } else if (prefArch.equals("armeabi-v7a (32-bit)")) {
            arch = "arm32";
        } else {
            arch = null;
            sendError("You selected invalid arch, work is stopped.", true, null);
        }

        if (prefType.equals("Unclone")) {
            type = "uc";
        } else if (prefType.equals("Clone")){
            type = "c";
        } else {
            type = null;
            sendError("You selected invalid installation type, work is stopped.", true, null);
        }

        logUtils.w("Work arch is " + arch + " and type is " + type);


        // Set AppPreferences

        appPreferences = new AppPreferences(
                preferences.getString("checker_interval", "4"),
                arch, type,
                preferences.getBoolean("send_notification", true),
                preferences.getBoolean("send_toast", true),
                preferences.getBoolean("use_mobile_data", false),
                preferences.getBoolean("12_hour_rule", false),
                preferences.getBoolean("use_instafel_api", true),
                preferences.getBoolean("disable_error_notifications", false),
                preferences.getBoolean("crash_logger", false));

        // Check Network Statues

        boolean mDataAllowStatus;

        try {
            if (isNetworkAvailable(getApplicationContext())) {
                if (isMobileDataConnected(getApplicationContext())) {
                    if (appPreferences.isAllowUseMobileData()) {
                        mDataAllowStatus = true;
                    } else {
                        mDataAllowStatus = false;
                    }
                } else {
                    mDataAllowStatus = true;
                }

                if (mDataAllowStatus) {
                    // Get IG's Version Code

                    String versionName;
                    if (type.equals("uc")) {
                        versionName = Utils.getAppVersionCode(getApplicationContext(), "com.instagram.android");
                    } else if (type.equals("c")) {
                        versionName = Utils.getAppVersionCode(getApplicationContext(), "com.instafel.android");
                    } else {
                        versionName = null;
                    }

                    if (versionName != null) {
                        if (versionName.equals("NOT_INSTALLED")) {
                            sendError("IG (Instafel / Instagram) is not installed. Please install from https://instafel.mamiiblt.me", false, null);
                        } else {
                            logUtils.w("Installed IG version is " + versionName);
                            try {
                                OkHttpClient client = new OkHttpClient();
                                String urlPart;
                                if (arch.equals("arm64")) {
                                    urlPart = "arm64-v8a";
                                } else {
                                    urlPart = "armeabi-v7a";
                                }
                                Request request;
                                if (appPreferences.isUseInstafelApi()) {
                                    request  = new Request.Builder()
                                            .url("https://api.mamiiblt.me/ifl/check?arch=" + arch)
                                            .build();
                                } else {
                                    request = new Request.Builder()
                                            .url("https://api.github.com/repos/mamiiblt/instafel_release_" + urlPart + "/releases/latest")
                                            .build();
                                }
                                Response response = client.newCall(request).execute();
                                if (response.isSuccessful()) {

                                    String version;
                                    String published_at;
                                    JSONObject res;
                                    if (appPreferences.isUseInstafelApi()) {
                                        res = new JSONObject(response.body().string());
                                        version = res.getString("ig_version");
                                        published_at = res.getString("published_at");
                                    } else {
                                        res = new JSONObject(response.body().string());
                                        version = res.getString("body").split("\n")[1].split("v")[1].split(" ")[0];
                                        published_at = res.getString("published_at");
                                    }

                                    if (versionName.equals(version)) {
                                        logUtils.w("Update not needed, app is up-to-date.");
                                        return Result.success();
                                    } else {
                                        logUtils.w("New version found " + version);
                                        if (appPreferences.isAllow12HourMode()) {
                                            String publishTs = published_at;
                                            OffsetDateTime parsedDateTime = OffsetDateTime.parse(publishTs, DateTimeFormatter.ISO_DATE_TIME);
                                            OffsetDateTime currentDateTime = OffsetDateTime.now(ZoneOffset.UTC);
                                            Duration duration = Duration.between(parsedDateTime, currentDateTime);

                                            if (duration.toHours() >= 12) {
                                                // allow
                                            } else {
                                                logUtils.w("Duration is " + duration.getSeconds() + ", so update stopped.");
                                                return Result.success();
                                            }
                                        }

                                        String b_download_url = null;
                                        if (appPreferences.isUseInstafelApi()) {
                                            if (type.equals("uc")) {
                                                b_download_url = res.getString("download_link_uc");
                                            } else {
                                                b_download_url = res.getString("download_link_c");
                                            }
                                        } else {
                                            JSONArray assets = res.getJSONArray("assets");
                                            for (int i = 0; i < assets.length(); i++) {
                                                JSONObject asset = assets.getJSONObject(i);
                                                if (asset.getString("name").contains("_" + type + "_")) {
                                                    b_download_url = asset.getString("browser_download_url");
                                                }
                                            }
                                        }

                                        if (b_download_url != null) {

                                            // DOWNLOAD & INSTALL UPDATE
                                            uVersion = version;
                                            Intent fgServiceIntent = new Intent(ctx, InstafelUpdateService.class);
                                            fgServiceIntent.putExtra("file_url", b_download_url);
                                            fgServiceIntent.putExtra("version", uVersion);
                                            fgServiceIntent.putExtra("disable_error_log", appPreferences.isDisable_error_notifications());
                                            ctx.startService(fgServiceIntent);
                                        } else {
                                            sendError("Updater can't found update asset!", true, null);
                                        }
                                    }
                                } else {
                                    sendError("Response code is not 200 (" + response.code() +").", false, null);
                                }
                            } catch (Exception e) {
                                sendError("Error while sending / reading API request", true, e);
                            }
                        }
                    } else {
                        sendError("versionCode is NULL", true, null);
                    }
                } else {
                    logUtils.w("Update couldn't checked because mobile data is enabled.");
                }
            } else {
                logUtils.w("We can't connect to Internet, check failed.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (appPreferences.isCrashLoggerEnabled()) {
                logUtils.w("ERROR: " + e.getMessage());
                logUtils.w("MSG: " + e.getMessage());
                logUtils.w("CLASS: " + e.getClass().toString());
                logUtils.w("TRACE: " + Log.getStackTraceString(e));
            }
        }

        return Result.success();
    }

    public boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = connectivityManager.getActiveNetwork();
            return connectivityManager.getNetworkCapabilities(network) != null;
        } else {
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected();
        }
    }

    public boolean isMobileDataConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);

            if (capabilities != null) {
                return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            }
        } else {
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) {
                return networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
            }
        }
        return false;
    }

    private void sendError(String message, boolean priority, Exception e) {
        new Handler(Looper.getMainLooper()).post(() -> {

            // WRITE LOG

            LogUtils logUtils = new LogUtils(getApplicationContext());
            logUtils.w("ERROR: " + message);

            if (e != null) {
                logUtils.w("MSG: " + e.getMessage());
                logUtils.w("CLASS: " + e.getClass().toString());
                logUtils.w("TRACE: " + Log.getStackTraceString(e));
            }

            // SHOW NOTIFICATION OR TOAST


            if (appPreferences.isDisable_error_notifications()) {
                if (priority) {
                    sendErrorNotif(message);
                }
            } else {
                sendErrorNotif(message);
            }
        });
    }

    private void sendErrorNotif(String message) {
        if (appPreferences.isAllowNotification()) {
            NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Warning Channel",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                notificationManager.createNotificationChannel(channel);
            }

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("Work Error")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());

        } else {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        }
    }
}
