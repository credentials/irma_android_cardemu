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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.TextView;
import org.irmacard.cardemu.identifiers.IdemixCredentialIdentifier;
import org.irmacard.cardemu.identifiers.IdemixIdentifier;
import org.irmacard.cardemu.identifiers.IdemixIdentifierList;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.info.AttributeDescription;
import org.irmacard.credentials.info.CredentialIdentifier;

import java.text.DateFormat;
import java.util.*;

public class ExpandableCredentialsAdapter extends BaseExpandableListAdapter {
    private Context context;
    private IdemixIdentifierList<CredentialIdentifier> credentials;
    private HashMap<IdemixCredentialIdentifier,Attributes> credentialAttributes;

    public ExpandableCredentialsAdapter(Context context) {
        this.context = context;
        this.credentials = new IdemixIdentifierList<>();
        this.credentialAttributes = null;
    }

    public void updateData(HashMap<IdemixCredentialIdentifier, Attributes> credentialAttributes) {
        this.credentialAttributes = credentialAttributes;
        this.credentials = new IdemixIdentifierList<>(credentialAttributes.keySet());

        Collections.sort(credentials, new Comparator<IdemixIdentifier<CredentialIdentifier>>() {
            @Override public int compare(IdemixIdentifier<CredentialIdentifier> i1,
                                         IdemixIdentifier<CredentialIdentifier> i2) {
                /* Compare them by their title without index suffix, so that if they identify
                 * the same credential type, then their ordering remains unchanged. */
                return i1.getUiTitle().compareTo(i2.getUiTitle());
            }
        });

        this.notifyDataSetChanged();
    }

    @Override
    public int getGroupCount() {
        return credentials.size();
    }

    @Override
    public int getChildrenCount(int i) {
        return credentials.get(i).getIdentifier().getCredentialDescription().getAttributes().size() + 1;
    }

    @Override
    public Object getGroup(int credential_idx) {
        return credentials.get(credential_idx);
    }

    @Override
    public Object getChild(int credential_idx, int attribute_idx) {
        if (attribute_idx == 0)
            // We insert a fake attribute in the view at position 0 showing the validity
            return new AttributeDescription("Validity", "Valid until");
        else
            // Attribute i gets shown at position i + 1
            return credentials.get(credential_idx).getIdentifier()
                    .getCredentialDescription().getAttributes().get(attribute_idx - 1);
    }

    @Override
    public long getGroupId(int credential_idx) {
        return credentials.get(credential_idx).getIdentifier()
                .getCredentialDescription().getIdentifier().hashCode();
    }

    @Override
    public long getChildId(int credential_idx, int attribute_idx) {
        return attribute_idx;
    }


    public Attributes getAttributes(IdemixCredentialIdentifier ici) {
        return credentialAttributes.get(ici);
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getGroupView(int credential_idx, boolean isExpanded, View convertView, ViewGroup parent) {
        IdemixCredentialIdentifier ici = credentials.getIdentifer(credential_idx);
        Attributes attrs = credentialAttributes.get(ici);

        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.credential_item, null);
        }

        TextView credential_name_field = (TextView) convertView.findViewById(R.id.credential_item_name);
        credential_name_field.setText(credentials.getUiTitle(ici));
        if (!attrs.isExpired()) // Since the convertView gets reused, the TextView might be grey from a previous usage
            credential_name_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkblue));
        else
            credential_name_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));

        return convertView;
    }

    @Override
    public View getChildView(int credential_idx, int attribute_idx, boolean isExpanded, View convertView, ViewGroup parent) {
        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.credential_item_attribute, null);
        }

        TextView attribute_name_field = (TextView) convertView.findViewById(R.id.credential_attribute_name);
        TextView attribute_value_field = (TextView) convertView.findViewById(R.id.credential_attribute_value);

        IdemixCredentialIdentifier ici = credentials.getIdentifer(credential_idx);
        Attributes attrs = credentialAttributes.get(ici);

        if (attribute_idx == 0) {
            String validDate = DateFormat.getDateInstance().format(attrs.getExpiryDate());
            attribute_name_field.setText("Valid until");
            attribute_value_field.setText(validDate);
            if (!attrs.isExpired()) {
                attribute_name_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));
                attribute_value_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));
            } else {
                attribute_name_field.setTextColor(convertView.getResources().getColor(R.color.irmared));
                attribute_value_field.setTextColor(convertView.getResources().getColor(R.color.irmared));
            }
        } else {
            String attribute_name = ici.getIdentifier()
                    .getCredentialDescription().getAttributes().get(attribute_idx - 1).getName();
            String attribute_value = new String(attrs.get(attribute_name));
            attribute_name_field.setText(attribute_name);
            attribute_value_field.setText(attribute_value);
            // Since the convertView gets reused, the TextView might be red from a previous usage
            attribute_name_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));
            attribute_value_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));
        }

        return convertView;
    }

    @Override
    public boolean isChildSelectable(int i, int i2) {
        return false;
    }
}
