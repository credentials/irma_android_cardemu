package org.irmacard.cardemu.preferences;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.support.annotation.NonNull;
import android.view.View;
import org.irmacard.cardemu.IRMApp;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.SchemeManagerDetailActivity;
import org.irmacard.cardemu.SchemeManagerHandler;
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
	protected void onClick() {
		Intent detailIntent = new Intent(fragment.getActivity(), SchemeManagerDetailActivity.class);
		detailIntent.putExtra("manager", manager.getName());
		fragment.getActivity().startActivity(detailIntent);
	}

	@Override
	protected void onBindView(@NonNull View view) {
		View deleteView = view.findViewById(R.id.trash_manager);

		if (deleteView != null) {
			deleteView.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) {
					SchemeManagerHandler.confirmAndDeleteManager(manager, getContext(), new Runnable() {
						@Override public void run() {
							fragment.repopulate();
						}
					});
				}
			});
		}

		super.onBindView(view);
	}
}
