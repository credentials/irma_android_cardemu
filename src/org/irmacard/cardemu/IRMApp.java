package org.irmacard.cardemu;

import android.app.Application;
import org.acra.ACRA;
import org.acra.ReportField;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;

@ReportsCrashes(
        formUri = "https://demo.irmacard.org/crashreportsviewer/www/submit.php",
        //formUri = "https://demo.irmacard.org/crashreportsviewer/www/test.php",
        formUriBasicAuthLogin = "irma",
        formUriBasicAuthPassword = "test",
        customReportContent = {
                ReportField.REPORT_ID,
                ReportField.INSTALLATION_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.DEVICE_FEATURES,
                //ReportField.PRODUCT,
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
        mode = ReportingInteractionMode.TOAST,
        resToastText = R.string.crash_toast_text)
public class IRMApp extends Application {
    private final static String metricServer = ""; // TODO!
    private final static long reportTimeInterval = 1000*60*60*24; // 1 day in milliseconds

    @Override
    public void onCreate() {
        super.onCreate();
        ACRA.init(this);
        try {
            ACRA.getErrorReporter().putCustomData("MAX_HEAP", Long.valueOf(Runtime.getRuntime().maxMemory()/1024/1024).toString());
            ACRA.getErrorReporter().putCustomData("CORES", Integer.valueOf(Runtime.getRuntime().availableProcessors()).toString());
        } catch (Exception e) {
        }
        MetricsReporter.init(this, metricServer, reportTimeInterval);
    }
}
