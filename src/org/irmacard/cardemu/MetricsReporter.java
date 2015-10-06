package org.irmacard.cardemu;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.acra.util.Installation;
import org.irmacard.metrics.common.ApplicationInformation;
import org.irmacard.metrics.common.DataLogger;
import org.irmacard.metrics.common.Measurement;
import org.irmacard.metrics.common.SessionToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics reporter class. Use it as follows:
 *
 * - Initialize it using init() in the Application class
 * - Get the instance using getInstance() (the constructor is private)
 * - Send reports using the report* methods.
 *
 * It is not always possible or appropriate to immediately send the new report to the metric server, so the instance
 * first always saves the new report to the shared settings. Then we check every 10 minutes if there is wifi. If so,
 * we send any measurements immediately. Additionally, if there is wifi AND it was 24 hours ago that we sent
 * aggegrate reports, we send them too.
 *
 * If the sending of the measurement of aggegrate was successful, it is removed from the shared settings. If not,
 * then it is not removed, but no error or exception is shown to anyone, since we will retry anyway in 10 minutes.
 * Thus failures are silent but not permanent.
 */
public class MetricsReporter {
	private static MetricsReporter instance;

	private Application application;
	private String metricServer;
	private long reportTimeInterval;

	// Metrics and meta-informatioon
	private ApplicationInformation applicationInformation;
	private SessionToken metricToken = null;
	private Map<String, DataLogger> aggregrates = null;
	private List<Measurement> measurements = null;

	// Stuff we need to do our work
	private Context context;
	private SharedPreferences settings;
	private Gson gson = new Gson();
	private Type measurementsType = new TypeToken<ArrayList<Measurement>>(){}.getType(); // for Gson
	private Type aggregratesType = new TypeToken<HashMap<String,DataLogger>>(){}.getType(); // for Gson
	private final HttpClient client = new HttpClient(gson);

	// Used to check every 10 mins if we should send something
	private Handler handler = new Handler();

	// Private: users should not use this to construct their own instance, but use getInstance()
	private MetricsReporter(Application application, String server, long interval) {
		this.application = application;
		this.metricServer = server;
		this.reportTimeInterval = interval;

		context = application.getApplicationContext();
		String acraID = Installation.id(context);
		applicationInformation = new ApplicationInformation(acraID, Build.MODEL, "irma_android_cardemu",
				getCurrentAppVersion());
		settings = application.getSharedPreferences("cardemu", 0);

		restoreMetricData();

		// Check every 10 minutes if there are metrics to be sent, and if we should send them
		handler.post(new Runnable() {
			@Override
			public void run() {
				sendAll();
				handler.postDelayed(this, 1000 * 60 * 10);
			}
		});
	}

	public static void init(Application application, String server, long interval) {
		instance = new MetricsReporter(application, server, interval);
	}

	/**
	 * Get the instance.
	 * @throws IllegalStateException if init() was not called before this method
	 */
	public static MetricsReporter getInstance() throws IllegalStateException {
		if (instance == null) {
			throw new IllegalStateException("MetricsReporter is not yet initialized; call init() first");
		}

		return instance;
	}

	/**
	 * Attempts to restore the metric data from the shared preferences.
	 */
	private void restoreMetricData() {
		String metricTokenJson = settings.getString("metricToken", "");
		String aggregratesJson = settings.getString("aggregrates", "");
		String measurementsJson = settings.getString("measurements", "");

		try {
			metricToken = gson.fromJson(metricTokenJson, SessionToken.class);
			aggregrates = gson.fromJson(aggregratesJson, aggregratesType);
			measurements = gson.fromJson(measurementsJson, measurementsType);
		} catch (JsonSyntaxException e) {
			e.printStackTrace();
		}

		if (metricToken == null) {
			getNewMetricToken();
		}
		if (aggregrates == null) {
			aggregrates = new HashMap<>();
		}
		if (measurements == null) {
			measurements = new ArrayList<>();
		}
	}

	/**
	 * Asynchroniously fetch a new SessionToken from the server. If successful, the new token will be put in the
	 * metricToken member. If not, that member will stay null.
	 */
	private void getNewMetricToken() {
		new AsyncTask<Void,Void,SessionToken>() {
			@Override
			protected SessionToken doInBackground(Void... params) {
				String url = metricServer + "/irma_metrics_server/api/v1/register";
				try {
					return client.doPost(SessionToken.class, url, applicationInformation);
				} catch (HttpClient.HttpClientException e) {
					e.printStackTrace();
					return null;
				}
			}

			@Override
			protected void onPostExecute(SessionToken token) {
				if (token != null) {
					MetricsReporter.this.metricToken = token;
					settings.edit()
							.putString("metricToken", gson.toJson(token))
							.apply();
				}
			}
		}.execute();
	}

	private String getCurrentAppVersion() {
		try {
			PackageInfo pi = context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0);
			return pi.versionName;
		} catch (PackageManager.NameNotFoundException e) {
			return "0";
		}
	}

	private boolean isWifiConnected() {
		ConnectivityManager connManager
				= (ConnectivityManager) application.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo wifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		return wifi.isConnected();
	}

	/**
	 * Send any metrics not yet sent, if we are on wifi and it is time to do so.
	 */
	private void sendAll() {
		if (metricToken == null) {
			getNewMetricToken();

			// The above method is asynchronious, so the new token won't be immediately available for the methods
			// below. So we just return here, and retry the next time we're called.
			return;
		}

		if (shouldSendAggregrates()) {
			sendAggregrates();
		}

		if (shouldSendMeasurements()) {
			sendMeasurements();
		}
	}

	private boolean shouldSendMeasurements() {
		return measurements.size() > 0
				&& isWifiConnected()
				&& metricToken != null;
	}

	private boolean shouldSendAggregrates() {
		long lastReport = settings.getLong("lastAggregratesSent", 0);
		long time = System.currentTimeMillis();

		boolean isNotEmpty = true;
		for (DataLogger logger : aggregrates.values()) {
			isNotEmpty = isNotEmpty && !logger.isEmpty();
		}

		return isNotEmpty
				&& isWifiConnected()
				&& time - lastReport > reportTimeInterval
				&& metricToken != null;
	}

	private void clearAggregrates() {
		aggregrates.clear();
		settings.edit().remove("aggregrates").apply();
	}

	private void clearMeasurements() {
		measurements.clear();
		settings.edit().remove("measurements").apply();
	}

	private void sendAggregrates() {
		new AsyncTask<Void,Void,Boolean>() {
			@Override
			protected Boolean doInBackground(Void... params) {
				String url = metricServer + "/irma_metrics_server/api/v1/aggregate";
				String auth = "Bearer " + metricToken.getSessionToken();

				try {
					for (DataLogger logger : aggregrates.values()) {
						client.doPost(Object.class, url, logger.report(), auth);
					}
					return true;
				} catch (HttpClient.HttpClientException e) {
					e.printStackTrace();
					return false;
				}
			}

			@Override
			protected void onPostExecute(Boolean success) {
				if (success) {
					clearAggregrates();
					settings.edit().putLong("lastAggregratesSent", System.currentTimeMillis()).apply();
				}
			}
		}.execute();
	}

	private void sendMeasurements() {
		new AsyncTask<Void,Void,Boolean>() {
			@Override
			protected Boolean doInBackground(Void... params) {
				String url = metricServer + "/irma_metrics_server/api/v1/measurement";
				String auth = "Bearer " + metricToken.getSessionToken();

				try {
					for (Measurement m : measurements) {
						client.doPost(Object.class, url, m, auth);
					}
					return true;
				} catch (HttpClient.HttpClientException e) {
					e.printStackTrace();
					return false;
				}
			}

			@Override
			protected void onPostExecute(Boolean success) {
				if (success) {
					clearMeasurements();
				}
			}
		}.execute();
	}

	// Reporting methods

	/**
	 * Save a measurement whose values should be aggregrated.
	 * @param key The name of the measurement
	 * @param value The value
	 */
	public void reportAggregrateMeasurement(String key, double value) {
		if (!aggregrates.containsKey(key)) {
			aggregrates.put(key, new DataLogger(key));
		}

		aggregrates.get(key).log(value);
		settings.edit()
				.putString("aggregrates", gson.toJson(aggregrates, aggregratesType))
				.apply();
	}

	/**
	 * Report a measurement. Will be sent to the metrics server immediately.
	 * @param key The name of the measurement
	 * @param value The value
	 */
	public void reportMeasurement(String key, double value) {
		reportMeasurement(key, value, true);
	}

	/**
	 * Report a measurement.
	 * @param key The name of the measurement
	 * @param value The value
	 * @param sendNow If we should send it now, or save it for later reporting
	 */
	public void reportMeasurement(String key, double value, boolean sendNow) {
		measurements.add(new Measurement(key, value));

		settings.edit()
				.putString("measurements", gson.toJson(measurements, measurementsType))
				.apply();

		if (sendNow && shouldSendMeasurements()) {
			sendMeasurements();
		}
	}
}
