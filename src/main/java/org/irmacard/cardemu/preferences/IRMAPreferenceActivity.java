package org.irmacard.cardemu.preferences;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.support.annotation.NonNull;
import android.view.MenuItem;
import android.webkit.URLUtil;

import org.irmacard.cardemu.R;

import java.util.List;

public class IRMAPreferenceActivity extends PreferenceActivity {
	public final static int ACTIVITY_CODE = 123;

	SchemeManagersPreferenceFragment schemeManagersFragment;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) // see comments in onResume()
			getIntent().putExtra("shouldProcessIntent", savedInstanceState.getBoolean("shouldProcessIntent", true));
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean("shouldProcessIntent", false); // see comments in onResume()
	}

	@Override
	public void onBuildHeaders(List<Header> target) {
		loadHeadersFromResource(R.xml.preferences_headers, target);
	}

	@Override
	protected boolean isValidFragment(String fragmentName) {
		return SchemeManagersPreferenceFragment.class.getName().equals(fragmentName)
				|| ScreenshotPreferenceFragment.class.getName().equals(fragmentName);
	}

	// Called by SchemeManagersPreferenceFragment.onCreate() so that we can tell it to
	// go download some new scheme manager
	public void setSchemeManagersFragment(SchemeManagersPreferenceFragment schemeManagersFragment) {
		this.schemeManagersFragment = schemeManagersFragment;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				if (getFragmentManager().getBackStackEntryCount() != 0)
					getFragmentManager().popBackStack();
				else
					onBackPressed();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onResume() {
		super.onResume();

		Intent intent = getIntent();
		String url = intent.getStringExtra("url");
		// TODO only accept HTTPS
		if (url == null || url.length() == 0 || !(URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)) )
			return; // Nothing to do

		/*
		 * Preventing the double handling of intents
		 * Whenever the activity is paused and then resumed for any reason, we get the intent that started
		 * the activity here, even if it has already been processed once. So we have to prevent it from
		 * being handled twice. We do this with the shouldProcessIntent flag.
		 * Note that if the activity is killed by the OS, then when it is resumed later on, the shouldProcessIntent
		 * flag is gone from the intent even if we have set it here at some point. For this reason we save it
		 * in the Bundle in onSaveInstanceState() just before the activity is killed, and we restore it, if
		 * present, to the intent in onCreate(), so that we can be certain that it has been set here.
		 */
		boolean shouldProcessIntent = intent.getBooleanExtra("shouldProcessIntent", true);
		intent.putExtra("shouldProcessIntent", false);
		if (!shouldProcessIntent)
			return;

		switch (intent.getAction()) {
			case Intent.ACTION_VIEW:
				// The user pressed an add-scheme-manager link in the browser. Since this activity is the receiver
				// for this type of intent, the preferences screen is opened but not the scheme managers fragment.
				// So we have to open the scheme managers fragment, by sending another intent to ourself, with a
				// different action so that we can tell the two intents apart.
				Intent i = new Intent(this, IRMAPreferenceActivity.class);
				i.setAction(Intent.ACTION_RUN);
				i.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SchemeManagersPreferenceFragment.class.getName());
				i.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true);
				i.putExtra("url", url);
				startActivity(i); // This starts a second instance of this class
				finish();         // so we should close this first instance to remove it from the task.
				break;
			case Intent.ACTION_RUN:
				// We received an intent opening the scheme managers fragment; do the downloading now
				schemeManagersFragment.confirmAndDownloadManager(url, this);
				break;
		}
	}
}
