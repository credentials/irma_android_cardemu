package org.irmacard.cardemu;

import android.app.Application;

import org.acra.*;
import org.acra.annotation.*;

/**
 * Created by fabianbr on 21-9-15.
 */

@ReportsCrashes(
        formUri = "",//TODO!
        customReportContent = {
                ReportField.REPORT_ID,
                ReportField.INSTALLATION_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.ANDROID_VERSION,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                //ReportField.PRODUCT,
                ReportField.INITIAL_CONFIGURATION,
                ReportField.CRASH_CONFIGURATION,
                ReportField.DISPLAY,
                ReportField.STACK_TRACE,
                ReportField.THREAD_DETAILS,
                ReportField.TOTAL_MEM_SIZE,
                ReportField.AVAILABLE_MEM_SIZE,
                ReportField.CUSTOM_DATA,
                ReportField.USER_APP_START_DATE,
                ReportField.USER_CRASH_DATE},
        mode = ReportingInteractionMode.TOAST)
public class IRMApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ACRA.init(this);
    }
}
