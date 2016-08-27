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


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Process;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.irmacard.cardemu.log.LogActivity;
import org.irmacard.cardemu.log.LogFragment;
import org.irmacard.cardemu.credentialdetails.CredentialDetailActivity;
import org.irmacard.cardemu.credentialdetails.CredentialDetailFragment;
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.cardemu.identifiers.IdemixCredentialIdentifier;
import org.irmacard.cardemu.preferences.IRMAPreferenceActivity;
import org.irmacard.cardemu.irmaclient.IrmaClient;
import org.irmacard.cardemu.irmaclient.IrmaClientHandler;
import org.irmacard.cardemu.selfenrol.EnrollSelectActivity;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.util.log.LogEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class MainActivity extends Activity {
	private static final int DETAIL_REQUEST = 101;

	private static final String TAG = "CardEmuMainActivity";
	private static final String SETTINGS = "cardemu";
	public static final int PERMISSION_REQUEST_CAMERA = 1;

	private SharedPreferences settings;

	// Previewed list of credentials
	private ExpandableCredentialsAdapter credentialListAdapter;

	private int activityState = STATE_IDLE;

	// New states
	public static final int STATE_IDLE = 1;
	public static final int STATE_CONNECTING_TO_SERVER = 2;
	public static final int STATE_CONNECTED = 3;
	public static final int STATE_READY = 4;
	public static final int STATE_COMMUNICATING = 5;
	public static final int STATE_WAITING_FOR_PIN = 6;

	// Timer for briefly displaying feedback messages on CardEmu
	private CountDownTimer cdt;
	private static final int FEEDBACK_SHOW_DELAY = 10000;
	private boolean showingFeedback = false;

	// Time after which old Intents are ignored (in milliseconds)
	private static final long INTENT_EXPIRY_TIME = 5000;

	private long qrScanStartTime;

	// Keep track of last verification url to ensure we handle it only once
	private String lastSessionUrl = "()";
	private String currentSessionUrl = "()";
	private boolean launchedFromBrowser;
	private boolean onlineEnrolling;

	private IrmaClientHandler irmaClientHandler = new IrmaClientHandler(this) {
		@Override public void onStatusUpdate(IrmaClient.Action action, IrmaClient.Status status) {
			switch (status) {
				case COMMUNICATING:
					setState(STATE_COMMUNICATING); break;
				case CONNECTED:
					setState(STATE_CONNECTED); break;
				case DONE:
					setState(STATE_IDLE); break;
			}
		}

		@Override public void onSuccess(IrmaClient.Action action) {
			switch (action) {
				case DISCLOSING:
					setFeedback(getString(R.string.disclosure_successful), "success"); break;
				case ISSUING:
					setFeedback(getString(R.string.issuing_succesful), "success"); break;
			}
			finish(true);
		}

		@Override public void onCancelled(IrmaClient.Action action) {
			switch (action) {
				case DISCLOSING:
					setFeedback(getString(R.string.disclosure_cancelled), "warning"); break;
				case ISSUING:
					setFeedback(getString(R.string.issuing_cancelled), "warning"); break;
			}
			finish(true);
		}

		@Override public void onFailure(IrmaClient.Action action, String message, ApiErrorMessage error, final String techInfo) {
			final String title;
			switch (action) {
				case DISCLOSING:
					title = getString(R.string.disclosure_failed); break;
				case ISSUING:
					title = getString(R.string.issuing_failed); break;
				case UNKNOWN:
				default:
					title = getString(R.string.failed); break;
			}

			final String feedback = title + ": " + message;
			setFeedback(title, "failure");
			finish(false);

			showErrorDialog(title, feedback, techInfo);
		}

		private void finish(boolean returnToBrowser) {
			setState(STATE_IDLE);

			lastSessionUrl = currentSessionUrl;
			currentSessionUrl = "";

			if (!onlineEnrolling && launchedFromBrowser && returnToBrowser)
				onBackPressed();

			onlineEnrolling = false;
			launchedFromBrowser = false;
		}
	};

	private void showErrorDialog(final String title, final String message, final String techInfo) {
		showErrorDialog(title, message, techInfo, false);
	}

	private void showErrorDialog(final String title, final String message,
	                             final String techInfo, final boolean showingTechInfo) {
		String m = message;
		if (showingTechInfo && techInfo != null)
			m += ". " + techInfo;

		AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
				.setIcon(R.drawable.irma_error)
				.setTitle(title)
				.setMessage(m)
				.setPositiveButton(R.string.dismiss, null);

		if (techInfo != null) {
			int buttonText = showingTechInfo ? R.string.lessinfo : R.string.techinfo;
			builder.setNeutralButton(buttonText, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface dialogInterface, int i) {
					showErrorDialog(title, message, techInfo, !showingTechInfo);
				}
			});
		}

		builder.show();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.i(TAG, "onCreate() called");

		// Set to true if IRMApp did not manage to deserialize our credentials
		// Since an Application cannot show an AlertDialog, we do it for it.
		if (IRMApp.attributeDeserializationError)
			showApplicationError();

		// Disable screenshots if we should
		if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("allow_screenshots", false))
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

		setContentView(R.layout.activity_main);

		// For some reason this textview ignores the android:textIsSelectable in its xml file, so that it catches
		// touch events that were meant for its container. Don't know why setting its value here works.
		((TextView) findViewById(R.id.feedback_text)).setTextIsSelectable(false);

		// Display cool list
		ExpandableListView credentialList = (ExpandableListView) findViewById(R.id.listView);
		credentialListAdapter = new ExpandableCredentialsAdapter(this);
		credentialList.setAdapter(credentialListAdapter);
		updateCredentialList();

		credentialList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long id) {
				try {
					IdemixCredentialIdentifier ici = (IdemixCredentialIdentifier) adapterView.getItemAtPosition(i);
					Log.i(TAG, "Credential with index " + i + " containing credential "
							+ ici.getIdentifier() + " was " + "longclicked");


					Intent detailIntent = new Intent(MainActivity.this, CredentialDetailActivity.class);
					detailIntent.putExtra(CredentialDetailFragment.ATTRIBUTES,
							credentialListAdapter.getAttributes(ici));
					detailIntent.putExtra(CredentialDetailFragment.HASHCODE, CredentialManager.getHashCode(ici));
					startActivityForResult(detailIntent, DETAIL_REQUEST);
				} catch (ClassCastException e) {
					Log.e(TAG, "Item " + i + " longclicked but was not a CredentialDescription");
				}

				return true;
			}
		});

		setState(STATE_IDLE);
		clearFeedback();

		settings = getSharedPreferences(SETTINGS, 0);
	}

	private void showApplicationError() {
		new AlertDialog.Builder(this)
				.setIcon(R.drawable.irma_error)
				.setTitle(R.string.cantreadattributes)
				.setMessage(R.string.cantreadattributes_long)
				.setNeutralButton(R.string.se_continue, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dialogInterface, int i) {
						settings.edit().remove(CredentialManager.CREDENTIAL_STORAGE).apply();
						try {
							CredentialManager.init(settings);
							updateCredentialList();
						} catch (CredentialsException e1) {
							// This couldn't possibly happen, but if it does, let's be safe
							throw new RuntimeException(e1);
						}
					}
				})
				.setPositiveButton(R.string.exit, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dialogInterface, int i) {
						Process.killProcess(Process.myPid());
						System.exit(1);
					}
				})
				.show();
	}

	@SuppressWarnings("unused")
	public int getState() {
		return activityState;
	}

	public void setState(int state) {
		Log.i(TAG, "Set state: " + state);
		activityState = state;

		switch (activityState) {
			case STATE_IDLE:
				updateCredentialList();
				break;
			default:
				break;
		}

		setUIForState();
	}

	private void setUIForState() {
		int imageResource = 0;
		int statusTextResource = 0;

		switch (activityState) {
			case STATE_IDLE:
				imageResource = R.drawable.irma_icon_place_card_520px;
				statusTextResource = R.string.status_idle;
				break;
			case STATE_CONNECTING_TO_SERVER:
				imageResource = R.drawable.irma_icon_place_card_520px;
				statusTextResource = R.string.status_connecting;
				break;
			case STATE_CONNECTED:
				imageResource = R.drawable.irma_icon_place_card_520px;
				statusTextResource = R.string.status_connected;
				break;
			case STATE_READY:
				imageResource = R.drawable.irma_icon_card_found_520px;
				statusTextResource = R.string.status_ready;
				break;
			case STATE_COMMUNICATING:
				imageResource = R.drawable.irma_icon_card_found_520px;
				statusTextResource = R.string.status_communicating;
				break;
			case STATE_WAITING_FOR_PIN:
				imageResource = R.drawable.irma_icon_card_found_520px;
				statusTextResource = R.string.status_waitingforpin;
				break;
			default:
				break;
		}

		((TextView) findViewById(R.id.status_text)).setText(statusTextResource);
		if (!showingFeedback)
			((ImageView) findViewById(R.id.statusimage)).setImageResource(imageResource);
	}

	public void setFeedback(String message, String state) {
		int imageResource = 0;

		setUIForState();

		if (state.equals("success")) {
			imageResource = R.drawable.irma_icon_ok_520px;
		}
		if (state.equals("warning")) {
			imageResource = R.drawable.irma_icon_warning_520px;
		}
		if (state.equals("failure")) {
			imageResource = R.drawable.irma_icon_missing_520px;
		}

		((TextView) findViewById(R.id.feedback_text)).setText(message);

		if (imageResource != 0) {
			((ImageView) findViewById(R.id.statusimage)).setImageResource(imageResource);
			showingFeedback = true;
		}

		if (cdt != null)
			cdt.cancel();

		cdt = new CountDownTimer(FEEDBACK_SHOW_DELAY, 1000) {
			public void onTick(long millisUntilFinished) {
			}

			public void onFinish() {
				clearFeedback();
			}
		}.start();
	}

	private void clearFeedback() {
		showingFeedback = false;
		((TextView) findViewById(R.id.feedback_text)).setText("");
		setUIForState();
	}

	protected void deleteAllCredentials() {
		if (activityState == STATE_IDLE) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(R.string.confirm_delete_all_title)
					.setMessage(R.string.confirm_delete_all_question)
					.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							Log.i(TAG, "We're idle, attempting removal of all credentials");

							CredentialManager.deleteAll();

							updateCredentialList();
						}
					})
					.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							// Cancelled
						}
					});
			AlertDialog dialog = builder.create();
			dialog.show();
		}

	}

	protected void tryDeleteCredential(int hashCode) {
		if (activityState != STATE_IDLE) {
			Log.i(TAG, "Delete long-click ignored in non-idle mode");
			return;
		}

		IdemixCredentialIdentifier ici = CredentialManager.findCredential(hashCode);
		if (ici == null)
			return;

		Log.w(TAG, "Deleting credential " + ici.toString());
		CredentialManager.delete(ici);
		updateCredentialList();
	}

	protected void updateCredentialList() {
		updateCredentialList(true);
	}

	protected void updateCredentialList(boolean tryDownloading) {
		// Can only be run when not connected to a server
		if (activityState != STATE_IDLE) {
			return;
		}

		if (tryDownloading) {
			CredentialManager.updateStores(new StoreManager.DownloadHandler() {
				@Override public void onSuccess() {
					updateCredentialList(false);
				}
				@Override public void onError(Exception e) {
					setFeedback(getString(R.string.downloading_credential_info_failed), "warning");
					updateCredentialList(false);
				}
			});
		}

		LinkedHashMap<IdemixCredentialIdentifier, Attributes> credentials = CredentialManager.getAllAttributes();
		credentialListAdapter.updateData(credentials);

		TextView noCredsText = (TextView) findViewById(R.id.no_credentials_text);
		Button enrollButton = (Button) findViewById(R.id.enroll_button);
		Button onlineEnrollButton = (Button) findViewById(R.id.online_enroll_button);
		int visibility = credentials.isEmpty() ? View.VISIBLE : View.INVISIBLE;

		if (noCredsText != null && enrollButton != null) {
			noCredsText.setVisibility(visibility);
			enrollButton.setVisibility(visibility);
			onlineEnrollButton.setVisibility(visibility);
		}
	}


	@Override
	protected void onPause() {
		super.onPause();
		Log.i(TAG, "onPause() called");

		settings.edit()
				.putString("lastSessionUrl", lastSessionUrl)
				.putString("currentSessionUrl", currentSessionUrl)
				.putBoolean("onlineEnrolling", onlineEnrolling)
				.putBoolean("launchedFromBrowser", launchedFromBrowser)
				.apply();
	}

	@Override
	public void onNewIntent(Intent intent) {
		setIntent(intent);
	}

	@Override
	protected void onDestroy() {
		Log.i(TAG, "onDestroy() called");
		super.onDestroy();
	}

	@Override
	protected void onResume() {
		super.onResume();
		Log.i(TAG, "onResume() called");

		lastSessionUrl = settings.getString("lastSessionUrl", "()");
		currentSessionUrl = settings.getString("currentSessionUrl", "()");
		onlineEnrolling = settings.getBoolean("onlineEnrolling", false);
		launchedFromBrowser = settings.getBoolean("launchedFromBrowser", false);

		updateCredentialList();
		processIntent();
	}

	private void processIntent() {
		Intent intent = getIntent();
		Log.i(TAG, "processIntent() called, action: " + intent.getAction());

		String qr = intent.getStringExtra("qr");
		if (!intent.getAction().equals(Intent.ACTION_VIEW) || qr == null)
			return;

		// The rest of this methoud should already prevent double intent handling, so checking
		// the timestamp might be superfluous. But let's let it stay for now just to be sure
		long timestamp  = intent.getLongExtra("timestamp", 0);
		if (timestamp > 0 && System.currentTimeMillis() - timestamp > INTENT_EXPIRY_TIME) {
			Log.i(TAG, "Discarding event, timestamp (" + timestamp + ") too old for qr: " + qr);
			return;
		}

		Log.i(TAG, "Received qr in intent: " + qr);
		if(qr.equals(currentSessionUrl) || qr.equals(lastSessionUrl)) {
			Log.i(TAG, "Already processed this qr");
			return;
		}

		currentSessionUrl = qr;
		launchedFromBrowser = true;
		IrmaClient.NewSession(qr, irmaClientHandler);
	}

	public void onMainShapeTouch(View v) {
		if (activityState != STATE_IDLE)
			return;

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				== PackageManager.PERMISSION_DENIED) {
			ActivityCompat.requestPermissions(this,
					new String[] { Manifest.permission.CAMERA }, PERMISSION_REQUEST_CAMERA);
		}
		else {
			startQRScanner(getString(R.string.scan_qr));
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
	                                       @NonNull int[] grantResults) {
		switch (requestCode) {
			case PERMISSION_REQUEST_CAMERA:
				if (grantResults.length > 0
						&& grantResults[0] == PackageManager.PERMISSION_GRANTED
						&& permissions[0].equals(Manifest.permission.CAMERA)) {
					startQRScanner(getString(R.string.scan_qr));
				}
				break;
		}
	}

	public void onEnrollButtonTouch(View v) {
		Intent i = new Intent(this, EnrollSelectActivity.class);
		CredentialManager.save();
		startActivityForResult(i, EnrollSelectActivity.EnrollSelectActivityCode);
	}

	public void onOnlineEnrollButtonTouch(View v) {
		onlineEnrolling = true;
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setData(Uri.parse("https://demo.irmacard.org/tomcat/irma_api_server/examples/issue-all.html"));
		startActivity(i);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == EnrollSelectActivity.EnrollSelectActivityCode && resultCode == RESULT_OK) {
			updateCredentialList();
		}
		else if (requestCode == DETAIL_REQUEST && resultCode == CredentialDetailActivity.RESULT_DELETE) {
			int hashCode = data.getIntExtra(CredentialDetailActivity.ARG_RESULT_DELETE, 0);
			if (hashCode != 0)
				tryDeleteCredential(hashCode);
		}
		else if (requestCode == IRMAPreferenceActivity.ACTIVITY_CODE) {
			if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("allow_screenshots", false))
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
			else
				getWindow().setFlags(
						WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
		}
		else {
			MetricsReporter.getInstance().reportAggregrateMeasurement("qr_scan_time", System.currentTimeMillis() - qrScanStartTime);

			IntentResult scanResult = IntentIntegrator
					.parseActivityResult(requestCode, resultCode, data);

			// Process the results from the QR-scanning activity
			if (scanResult != null) {
				String contents = scanResult.getContents();
				if (contents != null) {
					launchedFromBrowser = false;
					onlineEnrolling = false;
					IrmaClient.NewSession(contents, irmaClientHandler);
				}
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	public void startQRScanner(String message) {
		qrScanStartTime = System.currentTimeMillis();
		IntentIntegrator integrator = new IntentIntegrator(this);
		integrator.setPrompt(message);
		integrator.initiateScan();
	}

	@Override
	public void onBackPressed() {
		// When we are not in IDLE state, return there
		if (activityState != STATE_IDLE) {
			if (cdt != null)
				cdt.cancel();

			setState(STATE_IDLE);
			clearFeedback();
		} else {
			// We are in Idle, do what we always do
			super.onBackPressed();
		}
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.menu_manual_session).setVisible(BuildConfig.DEBUG);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Log.d(TAG, "menu press registered");
		// Handle item selection
		switch (item.getItemId()) {
			case R.id.preferences:
				Intent intent = new Intent();
				intent.setClassName(this, IRMAPreferenceActivity.class.getName());
				startActivityForResult(intent, IRMAPreferenceActivity.ACTIVITY_CODE);
				return true;
			case R.id.enroll:
				Log.d(TAG, "enroll menu item pressed");
				onEnrollButtonTouch(null);
				return true;
			case R.id.online_enroll:
				Log.d(TAG, "online enroll menu item pressed");
				onOnlineEnrollButtonTouch(null);
				return true;
			case R.id.show_card_log:
				Log.d(TAG, "show_card_log pressed");
				ArrayList<LogEntry> logs = new ArrayList<>(CredentialManager.getLog());
				logs = new ArrayList<>(logs.subList(0, Math.min(logs.size(), 250)));
				Intent logIntent = new Intent(this, LogActivity.class);
				logIntent.putExtra(LogFragment.ARG_LOG, logs);
				startActivity(logIntent);
				return true;
			case R.id.menu_clear:
				if (activityState == STATE_IDLE) {
					deleteAllCredentials();
					updateCredentialList();
				}
				return true;
			case R.id.menu_manual_session:
				startManualSession();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	private void startManualSession() {
		final EditText inputbox = new EditText(this);
		inputbox.setHint(R.string.qr_code_contents);

		new AlertDialog.Builder(this)
				.setTitle(R.string.manually_start_session)
				.setView(inputbox)
				.setPositiveButton(R.string.start, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dialog, int whichButton) {
						IrmaClient.NewSession(inputbox.getText().toString(), irmaClientHandler);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}
}
