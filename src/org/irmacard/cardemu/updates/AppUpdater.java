package org.irmacard.cardemu.updates;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.irmacard.cardemu.ByteArrayToBase64TypeAdapter;
import org.irmacard.cardemu.HttpClient;
import org.irmacard.cardemu.HttpClient.HttpClientException;
import org.irmacard.cardemu.R;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * App updater class. Upon instantiation, the following happens:
 * - The updateVersionInfo() method fetches update info from the server.
 * - When that is done, if it found there is a new version, then
 *   * if the user is connected to WiFi, updateVersionInfo() calls downloadNewestVersion() to download the new version,
 *   * if not, the user is asked for permission for downloading, using askDownloadPermission(). If he consents,
 *     downloadNewestVersion() is called; if not, nothing further happens.
 * - If and when downloadNewestVersion() completes with the downloading, a dialog is shown to the user, using
 *   showUpdateDialog(), informing an update will happen and asking him to agree in the next screen (which is
 *   provided by Android, not by us).
 * - When the user presses OK (which is the only button in our dialog), an intent is fired pointing to the downloaded
 *   .apk, which will trigger Android's update screen.
 *
 * Every 10 minutes, the class checks if we should recheck for new versions. If 24 hours have passed (see the
 * updateCheckDelay variable) since we last checked, the process above runs again. The process can also be run by
 * calling updateVersionInfo(true).
 */
public class AppUpdater {
	public final static String TAG = "AppUpdater";
	public final static String appName = "cardemu";
	public final static String currentVersionFileName = appName + "-current.json";
	public final static String versionInfoFileName = appName + "-%d.json";
	public final static String apkFileName = appName + "-%d.apk";
	public final static int updateCheckDelay = 1000*60*60*24; // How often we should check if there are new versions

	// Local cache of version info's
	final private Map<Integer, AppVersionInfo> newVersions = new HashMap<>();
	// Reference to the latest version info for convenience
	private AppVersionInfo newestVersionInfo;
	private int newestVersionCode;

	private Context context;
	private String serverUrl;
	private HttpClient httpClient;
	private Handler handler = new Handler();

	/**
	 * Construct a new updater, and asynchroniously fetch version info from the update server
	 *
	 * @param context Needed for firing the update intent, getting version info, constructing dialog
	 * @param serverUrl The server hosting versions and version info
	 * @throws IllegalArgumentException if the serverUrl doesn't start with https
	 */
	public AppUpdater(final Context context, String serverUrl) throws IllegalArgumentException {
		if (!serverUrl.startsWith("https")) {
			throw new IllegalArgumentException("Non-HTTPS updating is a bad idea");
		}

		if (!serverUrl.endsWith("/")) {
			serverUrl = serverUrl + "/";
		}

		this.context = context;
		this.serverUrl = serverUrl;

		// Since we use ByteArrayToBase64TypeAdapter, the SHA1 hash of the file in the .json must be in Base64. This
		// can be generated for example by this line:
		// openssl dgst -binary -sha1 file.apk | base64
		Gson gson = new GsonBuilder()
				.registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())
				.create();
		this.httpClient = new HttpClient(gson);

		updateVersionInfo(true);

		/**
		 * Check every 10 minutes if we should poll the server for new versions.
		 * Note that the counter that decides if those 10 minutes have passed runs only when the current thread is
		 * active, so we can't just put our desired check interval (24 hours) here, because then the time gap would
		 * only be actually 24 hours if the app is continuously in the foreground, which is not likely to happen.
		 */
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				updateVersionInfo(false);
				handler.postDelayed(this, 1000*60*10);
			}
		}, 1000*60*10);
	}

	/**
	 * Asynchroniously fetch new version info. If new versions are found, the newest will be downloaded (asking the user
	 * for consent if he is on cellular), after which the update will be triggered.
	 * @param force if true the server will always be checked for new versions; otherwise, the server will only be
	 *              checked if the last time we checked was more than updateCheckDelay milliseconds ago.
	 */
	public void updateVersionInfo(boolean force) {
		updateVersionInfo(force, false);
	}

	public void updateVersionInfo(boolean force, final boolean toast) {
		// If it's not imperative that we check now, and we already checked recently, don't do anything
		if (!force && !shouldUpdateVersionInfo())
			return;

		// AsyncTasks like these can only be executed once, so we have to recreate it each time this method is called.
		new AsyncTask<Void, Void, Void>() {
			boolean noneFound = true;
			boolean serverError = false;

			@Override
			protected Void doInBackground(Void... params) {
				try {
					// If there is no connectivity this just throws an exception, which is then ignored. But checking
					// for connectivity in appropriate ways (e.g. using a ConnectivityManager) seems to be nontrivial.
					fetchAllVersionInfos();
				} catch (HttpClientException e) {
					if (e.status == 404)
						noneFound = true;
					else
						// Report this exception
						serverError = true;
				}
				return null;
			}

			@Override
			protected void onPostExecute(Void param) {
				// Store that we've updated the version info cache at this time
				context.getSharedPreferences(TAG, 0)
						.edit()
						.putLong("last_update_check", Calendar.getInstance().getTimeInMillis())
						.apply();

				if (newVersions.size() > 0) {
					noneFound = false;
					ConnectivityManager connManager
							= (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
					NetworkInfo wifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

					// We might already have downloaded the newest version earlier
					String filename = String.format(apkFileName, newestVersionCode);
					if (context.getFileStreamPath(filename).exists() && newestVersionCode > getCurrentVersionCode()) {
						showUpdateDialog();
					}
					else {
						if (wifi.isConnected()) {
							// We're on wifi, just download the new version using another AsyncTask.
							// The corresponding onPostExecute() method will show a dialog informing the user the app
							// will now be updated, whose OK-button-handler fires the update.
							downloadNewestVersion();
						} else {
							// File might be a little big, ask for permission first. The OK-button-handler of the dialog
							// triggers the dowload.
							askDownloadPermission();
						}
					}
				} else {
					noneFound = true;
				}

				if (toast) {
					if (noneFound)
						Toast.makeText(context, R.string.no_new_updates, Toast.LENGTH_SHORT).show();
					if (serverError)
						Toast.makeText(context, R.string.update_server_error, Toast.LENGTH_SHORT).show();
				}
			}
		}.execute();
	}

	/**
	 * Check if we should update our version info cache.
	 *
	 * @return true if the last check was performed more than one day ago, false otherwise.
	 */
	private boolean shouldUpdateVersionInfo() {
		long lastCheck = context.getSharedPreferences(TAG, 0).getLong("last_update_check", 0L);
		return Calendar.getInstance().getTimeInMillis() - lastCheck > updateCheckDelay;
	}

	/**
	 * Get the current app version from the AndroidManifest.
	 *
	 * @return Current version
	 */
	public int getCurrentVersionCode() {
		PackageManager pm = context.getPackageManager();
		try {
			PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
			return pi.versionCode;
		} catch (PackageManager.NameNotFoundException ex) {
			return 0;
		}
	}

	/**
	 * Get the version info of the newest version that the server hosts. DO NOT CALL
	 * on the main thread, does networking.
	 *
	 * @return The version info
	 * @throws HttpClientException
	 */
	public AppVersionInfo getNewestVersionInfo() throws HttpClientException {
		return httpClient.doGet(AppVersionInfo.class, serverUrl + currentVersionFileName);
	}

	/**
	 * Get the version info of the specified version. DO NOT CALL on the main thread,
	 * does networking.
	 *
	 * @param version The version to lookup
	 * @return The version info
	 * @throws HttpClientException
	 */
	public AppVersionInfo getVersionInfo(int version) throws HttpClientException {
		String path = String.format(versionInfoFileName, version);
		return httpClient.doGet(AppVersionInfo.class, serverUrl + path);
	}

	/**
	 * Get the version info of all versions newer than the one currently running. DO NOT CALL
	 * on the main thread, does networking.
	 *
	 * @throws HttpClientException
	 */
	public void fetchAllVersionInfos() throws HttpClientException {
		int currentVersion = getCurrentVersionCode();
		newestVersionInfo = getNewestVersionInfo();
		newestVersionCode = newestVersionInfo.getVersionCode();

		newVersions.put(newestVersionInfo.getVersionCode(), newestVersionInfo);
		for (int i = currentVersion+1; i < newestVersionCode; ++i) {
			if (!newVersions.containsKey(i)) {
				newVersions.put(i, getVersionInfo(i));
			}
		}
	}

	/**
	 * Get a list of all changes of all versions newer than the one currently running from the local cache of version
	 * info's.
	 *
	 * @return A list containing the changes.
	 */
	public List<String> getAllChanges() {
		List<String> changes = new ArrayList<>();

		// First sort the versions, we want the oldest changes first in the array
		TreeSet<Integer> sortedVersionCodes = new TreeSet<>(newVersions.keySet());
		for (int versionCode : sortedVersionCodes) {
			changes.addAll(newVersions.get(versionCode).getChanges());
		}

		return changes;
	}

	/**
	 * (Asynchroniously) fetch the newest .apk from the server. Note that if something went wrong during the download
	 * (specifically, if the hash of the downloaded file turns out not to be what it should be), then this function
	 * does not retry the download.
	 *
	 * If the downloading was successful, then the update dialog will be shown to the user when done.
	 */
	private void downloadNewestVersion() {
		final int version = newestVersionInfo.getVersionCode();
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
					String filename = String.format(apkFileName, version);

					URL url = new URL(serverUrl + filename);
					URLConnection connection = url.openConnection();

					/**
					 * Since the actual installing of the apk is not handled by our app but by the android os, if we write
					 * our apk to the private storage of the cardemu app we need to ensure that it is readable by the Android
					 * apk installer. We can do this using the Context.MODE_WORLD_READABLE flag.
					 *
					 * The documentation of Context.MODE_WORLD_READABLE and much of the internet seem to agree on that using
					 * Context.MODE_WORLD_READABLE can be a security problem, but no one seems to take the trouble to explain
					 * why or in what situations. In this situation it seems to be much preferable to write the apk to
					 * internal storage and allow world read access using this flag over writing the apk to the external
					 * storage (i.e. the SD-card), because:
					 * - we would need an extra permission for that
					 * - we would get read and write permission to ALL of the SD-card, including any personal/sensitive files
					 *   the user may have there, and we really don't need or want such broad access,
					 * - any other app also has write access to our apk file, enabling anyone to do nasty stuff to our new
					 *   version.
					 *
					 * By storing it in local storage instead, we can at least disallow write access of other apps to our apk,
					 * and simultaneously I do not see a problem with other apps being able to read our apk. So this solution
					 * seems preferable to me over writing it in external storage, in all possible ways.
					 */
					FileOutputStream outputStream = context.openFileOutput(filename, Context.MODE_WORLD_READABLE);
					InputStream inputStream = connection.getInputStream();
					byte[] hash = copyInputStreamToOutputStream(inputStream, outputStream);

					// Cleanup
					outputStream.flush();
					outputStream.close();
					inputStream.close();

					// At other parts in this class we assume that if the apk is present, that it has been downloaded
					// successfully. So we should ensure right here that the apk is present only if nothing went wrong.
					// If something did, and the hashes do not match, we do not retry at this point.
					if (!MessageDigest.isEqual(hash, newestVersionInfo.getHash())) {
						context.deleteFile(filename);
					}
				} catch (IOException e) {
					// Report this exception
					Log.e(TAG, "Update error: ");
					e.printStackTrace();
				}
				return null;
			}

			@Override
			protected void onPostExecute(Void unused) {
				String filename = String.format(apkFileName, version);
				File apk = new File(context.getFilesDir(), filename);
				if (apk.exists()) {
					showUpdateDialog();
				}
			}
		}.execute();
	}


	/**
	 * Executes the update: fires an intent pointing to the downloaded .apk
	 */
	private void executeUpdate() {
		String filename = String.format(apkFileName, newestVersionInfo.getVersionCode());
		File apkfile = new File(context.getFilesDir(), filename);

		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setDataAndType(Uri.fromFile(apkfile), "application/vnd.android.package-archive");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	/**
	 * Asks the user for permission for downloading the update over the cellular connection. If the user agrees, the
	 * download will start and when done, the update will be triggered.
	 * NOTE: currently the dialog just claims that the .apk is about 4 MB big without having computed this number (it
	 * just always seems to be about this big). Might have to actually compute the size at some point.
	 */
	private void askDownloadPermission() {
		String message = "A new version is available for download, but you are currently not connected to a " +
				"WiFi network. The size of the update is about 4 MB. Do you want to download it over your cellular " +
				"connection?";

		new AlertDialog.Builder(context)
				.setTitle("New version available")
				.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						downloadNewestVersion();
					}
				})
				.setNegativeButton("No", null)
				.setMessage(message)
				.show();
	}

	/**
	 * Shows a dialog informing the user an update will happen.
	 */
	private void showUpdateDialog() {
		String message = "A new version has been downloaded and is ready to be installed, with the following changes:\n\n";
		for (String change : getAllChanges()) {
			message += "  • " + change + "\n";
		}
		message += "\nAfter pressing OK, a new screen will popup asking you for permission"
				+ " to update the app. Please press \"Install\" in this screen.";

		new AlertDialog.Builder(context)
				.setTitle("New version available")
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						executeUpdate();
					}
				})
				.setMessage(message)
				.show();
	}

	/**
	 * Helper function to copy an input stream to an output stream, calculating its SHA1 hash while we're at it.
	 *
	 * @return The SHA-1 hash of the bytes read from the specified inputstream.
	 */
	private static byte[] copyInputStreamToOutputStream(InputStream in, OutputStream out) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			DigestInputStream din = new DigestInputStream(in, digest);

			byte[] buffer = new byte[1024];
			int len;
			while ((len = din.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}

			return digest.digest();
		} catch (NoSuchAlgorithmException e) { // Will not happen
			return null;
		}
	}
}
