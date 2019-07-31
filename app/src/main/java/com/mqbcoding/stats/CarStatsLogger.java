package com.mqbcoding.stats;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

import com.github.martoreto.aauto.vex.CarStatsClient;
import com.github.martoreto.aauto.vex.FieldSchema;
import com.google.common.io.Files;
import com.google.gson.reflect.TypeToken;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public class CarStatsLogger implements CarStatsClient.Listener {
    private static final String TAG = "CarStatsLogger";

    private static final DateFormat LOG_FILENAME_DATE_FORMAT =
            new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
    private static final DateFormat JSON_DATE_FORMAT;

    private static final int AUTO_SYNC_TIMEOUT_MS = 60000;

    public static final String PREF_ENABLED = "statsLoggingActive";

    static {
        JSON_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.US);
        JSON_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private boolean mIsEnabled;
    private final String mPrefix;
    private final CarStatsClient mCarStatsClient;
    private GZIPOutputStream mLogStream;
    private Writer mLogWriter;
    private File mLogFile;
    private Collection<Listener> mListeners = new ArrayList<>();
    private Handler mHandler;
    private Gson mGson = new Gson();
    private boolean schemaNeedsUpdate = true;


    private LinkedBlockingQueue<Map<String,Object>> logQueue = new LinkedBlockingQueue<>();

    private abstract class CancelableRunnable implements Runnable {
        boolean canceled = false;

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }

        public boolean isCanceled() {
            return canceled;
        }
    }
    private CancelableRunnable logWorkerRunnable = new CancelableRunnable() {
        @Override
        public void run() {
            Log.i(TAG, "Starting Log Worker");

            while(!isCanceled()) {
                Map<String,Object> elem = null;
                try {
                    elem = logQueue.poll(5, TimeUnit.SECONDS);
                    if (elem!=null) {
                        // write

                        createLogStream();

                        //TODO: Sync?!!
                        mLogWriter.write(mGson.toJson(elem) + '\n');

                        scheduleSyncTimeout();
                    }
                } catch (InterruptedException e1) {
                    //e1.printStackTrace();
                } catch (IOException e) {
                    Log.w(TAG, "Error saving measurements", e);
                    closeWriter();
                }

            }

            Log.i(TAG, "Closing Log Worker");
        }
    };
    private Thread logWorker = new Thread(logWorkerRunnable);


    public CarStatsLogger(Context context, CarStatsClient statsClient, Handler handler, String prefix) {
        super();
        mHandler = handler;
        mPrefix = prefix;
        mCarStatsClient = statsClient;


        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.registerOnSharedPreferenceChangeListener(mPreferencesListener);
        readPreferences(sharedPreferences);

        logWorker.start();
    }

    public CarStatsLogger(Context context, CarStatsClient statsClient, Handler handler) {
        this(context, statsClient, handler, "car");
    }

    private void readPreferences(SharedPreferences preferences) {
        mIsEnabled = preferences.getBoolean(PREF_ENABLED, true);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final SharedPreferences.OnSharedPreferenceChangeListener mPreferencesListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
            readPreferences(sharedPreferences);

        }
    };

    public interface Listener {
        void onLogFileComplete(File logFile);
    }

    private void scheduleSyncTimeout() {
        mHandler.removeCallbacks(mSync);
        mHandler.postDelayed(mSync, AUTO_SYNC_TIMEOUT_MS);
    }

    private final Runnable mSync = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Auto-sync");
            closeWriter();
        }
    };

    @Override
    public void onNewMeasurements(String provider, Date date, Map<String, Object> values) {
        if (!mIsEnabled) {
            return;
        }

        Map<String, Object> o = new HashMap<>();
        o.put("timestamp", JSON_DATE_FORMAT.format(date));
        for (Map.Entry<String, Object> measurement : values.entrySet()) {
            String key = makeJsonKey(measurement.getKey());
            o.put(key, measurement.getValue());
        }

        logQueue.add(o);
    }

    public static String makeJsonKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9.]", "_");
    }
    @Override
    public void onSchemaChanged() {
        schemaNeedsUpdate = true;
    }

    public static File getLogsDir() throws IOException {
        File logsDir = new File(Environment.getExternalStorageDirectory(), "CarLogs");
        if (!logsDir.exists()) {
            if (!logsDir.mkdirs()) {
                Log.e(TAG, "Failed to create CarLogs folder: " + logsDir.getAbsolutePath());
                throw new IOException("Failed to create CarLogs folder");
            }
        }
        return logsDir;
    }

    public static File getSchemaFile() throws IOException {
        return new File(getLogsDir(), "schema.json");
    }

    private synchronized void createLogStream() throws IOException {
        if (schemaNeedsUpdate) {
            updateSchema();
        }
        if (mLogWriter != null) {
            return;
        }
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return;
        }

        String formattedDate = LOG_FILENAME_DATE_FORMAT.format(new Date());
        mLogFile = new File(getLogsDir(), mPrefix + "-" + formattedDate + ".log.gz");
        mLogStream = new GZIPOutputStream(new FileOutputStream(mLogFile));
        mLogWriter = new OutputStreamWriter(mLogStream,
                StandardCharsets.UTF_8);
        Log.i(TAG, "Started log file: " + mLogFile.getAbsolutePath());
    }

    private void updateSchema() throws IOException {
        Log.d(TAG, "Updating schema...");
        Map<String, Object> schema = null;
        File schemaFile = getSchemaFile();
        Type type = new TypeToken<Map<String, Object>>(){}.getType();
        if (schemaFile.exists()) {
            schema = mGson.fromJson(Files.asCharSource(schemaFile, StandardCharsets.UTF_8).read(),
                    type);
        }
        if (schema == null) {
            schema = new HashMap<>();
        }
        for (Map.Entry<String, FieldSchema> e: mCarStatsClient.getSchema().entrySet()) {
            if (!schema.containsKey(e.getKey())) {
                Log.d(TAG, "  New schema key: " + e.getKey() + " " + mGson.toJson(e.getValue()));
            }
        }
        schema.putAll(mCarStatsClient.getSchema());
        Files.asCharSink(schemaFile, StandardCharsets.UTF_8).write(mGson.toJson(schema, type));
        schemaNeedsUpdate = false;
    }

    public  synchronized void closeWriter(){
        if (mLogWriter != null) {
            try {
                mLogWriter.flush();
                mLogWriter.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing log stream", e);
            }
            try {
                mLogStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing log stream", e);
            }
            for (Listener listener: mListeners) {
                try {
                    listener.onLogFileComplete(mLogFile);
                } catch (Exception e) {
                    Log.e(TAG, "Error from listener", e);
                }
            }
            mLogWriter = null;
            mLogStream = null;
            mLogFile = null;
            mHandler.removeCallbacks(mSync);
        }
    }


    public synchronized void close() {
       closeWriter();

       try {
           logWorkerRunnable.setCanceled(true);
           logWorker.interrupt();
       } catch (Exception e) {
           e.printStackTrace();
       }
    }

    public void registerListener(Listener listener) {
        mListeners.add(listener);
    }
}
