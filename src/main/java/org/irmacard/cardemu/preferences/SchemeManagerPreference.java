package org.irmacard.cardemu.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.preference.Preference;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;
import org.irmacard.cardemu.IRMApp;
import org.irmacard.cardemu.R;
import org.irmacard.credentials.info.SchemeManager;

/** Represents a scheme manager. */
public class SchemeManagerPreference extends Preference {
	private SchemeManager manager;
	private SchemeManagersPreferenceFragment fragment;

	public SchemeManagerPreference(SchemeManagersPreferenceFragment fragment, Context context, SchemeManager manager) {
		super(context);
		this.fragment = fragment;
		this.manager = manager;

		String name = manager.getName();
		setKey("scheme-" + name);
		setTitle(manager.getHumanReadableName());
		setSummary(manager.getDescription());

		if (IRMApp.getStoreManager().canRemoveSchemeManager(name))
			setWidgetLayoutResource(R.layout.preference_delete_widget);
	}

	@Override
	protected void onBindView(@NonNull View view) {
		View deleteView = view.findViewById(R.id.trash_manager);

		if (deleteView != null) {
			deleteView.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) {
					new AlertDialog.Builder(getContext())
							.setIcon(android.R.drawable.ic_dialog_alert)
							.setTitle("Remove scheme manager?")
							.setMessage("Are you certain you want to remove this scheme manager? " +
									"This renders all credentials that you might have that fall under " +
									"the authority of this scheme manager unusable.")
							.setNegativeButton(android.R.string.cancel, null)
							.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
								@Override public void onClick(DialogInterface dialog, int which) {
									Log.i("SchemePrefs", "Deleting scheme manager " + manager.getName());
									IRMApp.getStoreManager().removeSchemeManager(manager.getName());
									fragment.repopulate();
								}
							})
							.show();
				}
			});
		}

		super.onBindView(view);
	}
}
