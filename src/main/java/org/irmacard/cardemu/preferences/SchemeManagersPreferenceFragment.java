package org.irmacard.cardemu.preferences;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.util.Log;

import org.irmacard.cardemu.R;
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.cardemu.IRMApp;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.SchemeManager;

/**
 * PreferenceFragment for managing scheme managers.
 */
public class SchemeManagersPreferenceFragment
		extends PreferenceFragment implements Preference.OnPreferenceChangeListener {
	private DescriptionStore descriptionStore;

	// If the activity is paused or killed, then it can't show any feedback; we keep track of this here
	private boolean cancelFeedback = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Since the IRMAPreferenceActivity is the one that gets any download-new-scheme-manager intents, it
		// needs to know where to find us
		((IRMAPreferenceActivity) getActivity()).setSchemeManagersFragment(this);

		descriptionStore = DescriptionStore.getInstance();
	}

	public static void confirmAndDeleteManager(final SchemeManager manager,
	                                           final Context context,
	                                           final Runnable runnable) {
		new AlertDialog.Builder(context)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.remove_schememanager_title)
				.setMessage(R.string.remove_schememanager_question)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dialog, int which) {
						Log.i("SchemePrefs", "Deleting scheme manager " + manager.getName());
						IRMApp.getStoreManager().removeSchemeManager(manager.getName());
						if (runnable != null)
							runnable.run();
					}
				})
				.show();
	}

	public void confirmAndDownloadManager(final String url, final Context context) {
		if (descriptionStore.containsSchemeManager(url)) {
			new AlertDialog.Builder(context)
					.setTitle(R.string.schememanager_known_title)
					.setMessage(R.string.schememanager_known_text)
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.add_schememanager_title) // TODO probably have this dialog be more informative
				.setMessage(context.getString(R.string.add_schememanager_question, url))
				.setNegativeButton(android.R.string.no, null)
				.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dialog, int which) {
						downloadManager(url, context);
					}
				})
				.show();
	}

	private void downloadManager(String url, final Context context) {
		cancelFeedback = false; // If we're here, the activity is still alive so that it can show feedback

		final ProgressDialog progressDialog = new ProgressDialog(context);
		progressDialog.setMessage(context.getString(R.string.downloading));
		progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
		progressDialog.setIndeterminate(true);
		progressDialog.setCanceledOnTouchOutside(false);
		progressDialog.show();

		StoreManager.downloadSchemeManager(url, new StoreManager.DownloadHandler() {
			@Override public void onSuccess() {
				if (cancelFeedback)
					return;

				progressDialog.dismiss();
				repopulate();
			}
			@Override public void onError(Exception e) {
				if (cancelFeedback)
					return;

				progressDialog.cancel();
				new AlertDialog.Builder(context)
						.setTitle(R.string.downloading_schememanager_failed_title)
						.setMessage(context.getString(R.string.downloading_schememanager_failed_text, e.getMessage()))
						.setIcon(android.R.drawable.ic_dialog_alert)
						.setPositiveButton(android.R.string.ok, null)
						.show();
			}
		});
	}

	@Override
	public void onPause() {
		super.onPause();
		cancelFeedback = true;
	}

	@Override
	public void onResume() {
		super.onResume();
		repopulate();
		cancelFeedback = false;
	}

	// We explicitly implement Preference.OnPreferenceChangeListener here instead of using an
	// anonymous class because of the following issue:
	// https://developer.android.com/reference/android/content/SharedPreferences.html#registerOnSharedPreferenceChangeListener%28android.content.SharedPreferences.OnSharedPreferenceChangeListener%29
	@Override
	public boolean onPreferenceChange(Preference preference, Object newValue) {
		confirmAndDownloadManager((String) newValue, getPreferenceScreen().getContext());

		// Don't persist the value that was entered as it is not an actual preference
		return false;
	}

	/**
	 * (Re)load all scheme managers and the "Add new" button
	 */
	public void repopulate() {
		setPreferenceScreen(null);
		addPreferencesFromResource(R.xml.preferences_schememanagers);

		PreferenceScreen screen = getPreferenceScreen();
		EditTextPreference addNewManager = (EditTextPreference) findPreference("add_new_manager");
		addNewManager.setOnPreferenceChangeListener(this);
		addNewManager.setText("");

		int order = 0;
		for (SchemeManager manager : descriptionStore.getSchemeManagers()) {
			SchemeManagerPreference pref = new SchemeManagerPreference(this, screen.getContext(), manager);
			pref.setOrder(++order);
			screen.addPreference(pref);
		}
	}
}
