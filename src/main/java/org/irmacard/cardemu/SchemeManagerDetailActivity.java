package org.irmacard.cardemu;

import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.SchemeManager;


public class SchemeManagerDetailActivity extends Activity {
	SchemeManager manager;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_scheme_manager_detail);

		String managerName = getIntent().getStringExtra("manager");
		manager = DescriptionStore.getInstance().getSchemeManager(managerName);

		setTitle(manager.getHumanReadableName());

		((TextView) findViewById(R.id.manager_name_value)).setText(manager.getName());
		((TextView) findViewById(R.id.manager_url_value)).setText(manager.getUrl());
		((TextView) findViewById(R.id.manager_description_value)).setText(manager.getDescription());
		((TextView) findViewById(R.id.manager_contact_value)).setText(manager.getContactInfo());

		findViewById(R.id.trash_manager).setVisibility(
				IRMApp.getStoreManager().canRemoveSchemeManager(managerName) ? View.VISIBLE : View.INVISIBLE);
	}

	public void onDeleteButtonClick(View view) {
		SchemeManagerHandler.confirmAndDeleteManager(manager, this, new Runnable() {
			@Override public void run() {
				finish();
			}
		});
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}
}
