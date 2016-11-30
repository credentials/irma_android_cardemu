package org.irmacard.cardemu.preferences;

import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import org.irmacard.cardemu.R;
import org.irmacard.cardemu.store.SchemeManagerHandler;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.SchemeManager;

/**
 * PreferenceFragment for managing scheme managers.
 */
public class SchemeManagersPreferenceFragment
		extends PreferenceFragment implements Preference.OnPreferenceChangeListener {
	private SchemeManagerHandler downloader = new SchemeManagerHandler();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Since the IRMAPreferenceActivity is the one that gets any download-new-scheme-manager intents, it
		// needs to know where to find us
		((IRMAPreferenceActivity) getActivity()).setSchemeManagersFragment(this);
	}

	@Override
	public void onPause() {
		super.onPause();
		if (downloader != null)
			downloader.setCancelFeedback(false);
	}

	@Override
	public void onResume() {
		super.onResume();
		repopulate();
		if (downloader != null)
			downloader.setCancelFeedback(true);
	}

	// We explicitly implement Preference.OnPreferenceChangeListener here instead of using an
	// anonymous class because of the following issue:
	// https://developer.android.com/reference/android/content/SharedPreferences.html#registerOnSharedPreferenceChangeListener%28android.content.SharedPreferences.OnSharedPreferenceChangeListener%29
	@Override
	public boolean onPreferenceChange(Preference preference, Object newValue) {
		downloader.confirmAndDownloadManager((String) newValue, getActivity(), new Runnable() {
			@Override public void run() {
				repopulate();
			}
		});

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
		for (SchemeManager manager : DescriptionStore.getInstance().getSchemeManagers()) {
			SchemeManagerPreference pref = new SchemeManagerPreference(this, screen.getContext(), manager);
			pref.setOrder(++order);
			screen.addPreference(pref);
		}
	}
}
