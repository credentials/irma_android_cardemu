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

package org.irmacard.cardemu.credentialdetails;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.TextViewCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.irmacard.cardemu.R;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.info.AttributeDescription;
import org.irmacard.credentials.info.CredentialDescription;

/**
 * Contains a method that dynamically adds attributes to a LinearLayout.
 *
 * Preferably we would do this using Fragments, but it seems to be completely impossible to add a fragment to alert
 * dialogs using AlertDialog with yes/no buttons.
 */
public class AttributesRenderer {
	Context context;
	LayoutInflater inflater;

	public AttributesRenderer(Context context, LayoutInflater inflater) {
		this.context = context;
		this.inflater = inflater;
	}

	/**
	 * Render the specified attributes, adding them to the specified list.
	 *
	 * @param attributes The attributes.
	 * @param list The list to add the rendered attributes to.
	 * @param includeTitle If true, the title of the credential will also be rendered and the attributes get a bullet
	 *                     in front of them (creating an unordered list).
	 * @param disclosedAttributes If specified, those attributes found in the credential but not here will be shown in
	 *                            light grey to indicate they are not being disclosed.
	 */
	@SuppressLint("SetTextI18n")
	public void render(Attributes attributes, LinearLayout list, boolean includeTitle, Attributes
			disclosedAttributes) {
		CredentialDescription cd = attributes.getCredentialDescription();

		if (includeTitle) {
			TextView attrName = new TextView(context);
			TextViewCompat.setTextAppearance(attrName, R.style.DetailHeading);
			attrName.setText(cd.getName());
			attrName.setTextColor(Color.BLACK);
			list.addView(attrName);
		}

		for (AttributeDescription desc : cd.getAttributes()) {
			@SuppressLint("InflateParams") View attributeView = inflater.inflate(R.layout.row_attribute, null);

			TextView name = (TextView) attributeView.findViewById(R.id.detail_attribute_name);
			TextView value = (TextView) attributeView.findViewById(R.id.detail_attribute_value);

			if (disclosedAttributes != null) {
				if (disclosedAttributes.get(desc.getName()) != null) {
					value.setTextColor(Color.BLACK);
					TextViewCompat.setTextAppearance(value, R.style.DetailHeading);
				} else {
					name.setTextColor(ContextCompat.getColor(context, R.color.irmagrey));
					value.setTextColor(ContextCompat.getColor(context, R.color.irmagrey));
				}
			}

			if (includeTitle) {
				name.setText(" \u2022 " + desc.getName() + ":");
			} else {
				name.setText(desc.getName() + ":");
			}
			value.setText(new String(attributes.get(desc.getName())));

			list.addView(attributeView);
		}
	}

}
