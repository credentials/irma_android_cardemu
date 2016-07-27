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

package org.irmacard.cardemu.disclosuredialog;

import android.content.Context;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.TextView;
import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.identifiers.IdemixAttributeIdentifier;
import org.irmacard.credentials.info.AttributeIdentifier;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Adapter for picking an attribute from a {@link AttributeDisjunction}, for use in a {@link SessionDialogFragment}.
 */
public class AttributesPickerAdapter extends BaseAdapter {
	Context context;
	LayoutInflater inflater;
	AttributeDisjunction disjunction;
	HashMap<IdemixAttributeIdentifier, String> candidates;
	ArrayList<IdemixAttributeIdentifier> identifiers;
	int selected;
	int index;

	/**
	 * Constructs a new adapter.
	 * @param context The context from which to take the layout inflater
	 * @param disjunction The disjunction from which we should pick an attribute
	 * @param index The index in the containing {@link DisclosureProofRequest} (for fetching with
	 * {@link #getIndex()})
	 */
	public AttributesPickerAdapter(Context context, AttributeDisjunction disjunction, int index) {
		this.context = context;
		this.disjunction = disjunction;
		this.index = index;
		candidates = CredentialManager.getCandidates(disjunction);
		identifiers = new ArrayList<>(candidates.keySet());
		inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	}

	@Override
	public int getCount() {
		return candidates.size();
	}

	@Override
	public Object getItem(int position) {
		IdemixAttributeIdentifier iai = identifiers.get(position);
		return candidates.get(iai);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		IdemixAttributeIdentifier ai = identifiers.get(position);

		TextView view;
		if (convertView == null)
			view = (TextView) inflater.inflate(R.layout.attribute_picker_text, parent, false);
		else
			view = (TextView) convertView;

		view.setText(candidates.get(ai));

		return view;
	}

	@Override
	public View getDropDownView(int position, View convertView, ViewGroup parent) {
		// TODO reuse convertView?

		View view = inflater.inflate(R.layout.attribute_picker_item, parent, false);
		IdemixAttributeIdentifier iai = identifiers.get(position);
		AttributeIdentifier ai = iai.getIdentifier();

		final CheckedTextView value = (CheckedTextView) view.findViewById(R.id.detail_attribute_value);

		String attrVal = ai.isCredential() ? "(possession of credential)" : candidates.get(iai);

		// The spaces (&nbsp;) push the radio button a bit to the right. Not very pretty but I can see no other way
		// to do it. (We have to add them to both lines because we do not know which one will be longest.)
		String html ="<b>" + iai.getUiTitle() + "&nbsp;&nbsp;&nbsp;</b><br/>"
				+ attrVal + "&nbsp;&nbsp;&nbsp;";
		value.setText(Html.fromHtml(html));
		value.setChecked(position == selected);

		return view;
	}

	/**
	 * Select the attribute at the specified position.
	 * @param position Zero-based number specifying the selected attribute
	 * @return The {@link AttributeDisjunction} that contained the attribute, with the selected member set
	 */
	public IdemixAttributeIdentifier findAndSelect(int position) {
		selected = position;
		return identifiers.get(position);
	}

	/**
	 * Gets the index in the containing {@link DisclosureProofRequest} passed to us in the constructor.
	 */
	public int getIndex() {
		return index;
	}
}
