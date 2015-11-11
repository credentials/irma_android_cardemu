package org.irmacard.cardemu;

import android.app.Application;
import org.acra.ACRA;
import org.acra.ACRAConfiguration;
import org.acra.ReportField;
import org.acra.annotation.ReportsCrashes;

@ReportsCrashes(
        formUri = BuildConfig.acraServer,
        formUriBasicAuthLogin = BuildConfig.acraLogin,
        formUriBasicAuthPassword = BuildConfig.acraPassword,
        customReportContent = {
                ReportField.REPORT_ID,
                ReportField.INSTALLATION_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.DEVICE_FEATURES,
                ReportField.INITIAL_CONFIGURATION,
                ReportField.CRASH_CONFIGURATION,
                ReportField.DISPLAY,
                ReportField.STACK_TRACE,
                ReportField.LOGCAT,
                ReportField.EVENTSLOG,
                ReportField.THREAD_DETAILS,
                ReportField.TOTAL_MEM_SIZE,
                ReportField.AVAILABLE_MEM_SIZE,
                ReportField.CUSTOM_DATA,
                ReportField.USER_APP_START_DATE,
                ReportField.USER_CRASH_DATE},
        resToastText = R.string.crash_toast_text)
public class IRMApp extends Application {
    private final static long reportTimeInterval = 1000*60*60*24; // 1 day in milliseconds

    @Override
    public void onCreate() {
        super.onCreate();
        ACRA.init(this);
        try {
            // In the annotation above we can set the mode only to literal enums, as opposed to the BuildConfig
            // expression below. So we extract the annotation and fix it manually
            final ACRAConfiguration configuration = new ACRAConfiguration(IRMApp.class.getAnnotation(ReportsCrashes.class));
            configuration.setMode(BuildConfig.acraMode);
            ACRA.init(this, configuration);

            ACRA.getErrorReporter().putCustomData("IS_DEBUG_BUILD", Boolean.toString(BuildConfig.DEBUG));
            ACRA.getErrorReporter().putCustomData("MAX_HEAP", Long.valueOf(Runtime.getRuntime().maxMemory()/1024/1024).toString());
            ACRA.getErrorReporter().putCustomData("CORES", Integer.valueOf(Runtime.getRuntime().availableProcessors()).toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

        MetricsReporter.init(this, BuildConfig.metricServer, reportTimeInterval);
        CardManager.init(getSharedPreferences("cardemu", 0));
    }
}
