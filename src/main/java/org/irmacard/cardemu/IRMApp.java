/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.cardemu;

import android.app.Application;
import org.acra.ACRA;
import org.acra.ACRAConfiguration;
import org.acra.ReportField;
import org.acra.annotation.ReportsCrashes;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.irmacard.android.util.credentials.AndroidFileReader;
import org.irmacard.android.util.credentials.StoreManager;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.info.IdemixKeyStoreDeserializer;
import org.irmacard.credentials.info.*;
import org.irmacard.credentials.util.log.LogEntry;

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
    private static StoreManager storeManager;

    @Override
    public void onCreate() {
        super.onCreate();

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

        // Setup the DescriptionStore and IdemixKeyStore
        FileReader reader = new AndroidFileReader(this);
        HttpClient client = new DefaultHttpClient();
        try {
            storeManager = new StoreManager(this);
            DescriptionStore.initialize(new DescriptionStoreDeserializer(reader), storeManager, client);
            IdemixKeyStore.initialize(new IdemixKeyStoreDeserializer(reader), storeManager, client);
        } catch (InfoException e) { // Can't do anything in this case
            throw new RuntimeException(e);
        }

        GsonUtil.addTypeAdapter(LogEntry.class, new LogEntrySerializer());

        MetricsReporter.init(this, BuildConfig.metricServer, reportTimeInterval);
        CredentialManager.init(getSharedPreferences("cardemu", 0));
    }

    public static StoreManager getStoreManager() {
        return storeManager;
    }
}
