package org.irmacard.cardemu;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListAdapter;
import android.widget.TextView;

import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.info.AttributeDescription;
import org.irmacard.credentials.info.CredentialDescription;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

/**
 * Created by wouter on 2/13/15.
 */
public class ExpandableCredentialsAdapter extends BaseExpandableListAdapter {
    private Context context;
    private List<CredentialDescription> credentials;
    private HashMap<CredentialDescription,Attributes> credentialAttributes;
    private String TAG = "ECA";

    public ExpandableCredentialsAdapter(Context context) {
        this.context = context;
        this.credentials = new ArrayList<CredentialDescription>();
        this.credentialAttributes = null;
    }

    public ExpandableCredentialsAdapter(Context context, List<CredentialDescription> credentials, HashMap<CredentialDescription,Attributes> credentialAttributes) {
        this.context = context;
        this.credentials = credentials;
        this.credentialAttributes = credentialAttributes;
    }

    public void updateData(List<CredentialDescription> credentials, HashMap<CredentialDescription,Attributes> credentialAttributes) {
        this.credentials = credentials;
        this.credentialAttributes = credentialAttributes;
        this.notifyDataSetChanged();
    }

    @Override
    public int getGroupCount() {
        return credentials.size();
    }

    @Override
    public int getChildrenCount(int i) {
        return credentials.get(i).getAttributes().size() + 1;
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
            return credentials.get(credential_idx).getAttributes().get(attribute_idx - 1);
    }

    @Override
    public long getGroupId(int credential_idx) {
        return credentials.get(credential_idx).getId();
    }

    @Override
    public long getChildId(int credential_idx, int attribute_idx) {
        return attribute_idx;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getGroupView(int credential_idx, boolean isExpanded, View convertView, ViewGroup parent) {
        CredentialDescription cd = credentials.get(credential_idx);
        String credential_name = cd.getName();
        Attributes attrs = credentialAttributes.get(cd);

        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.credential_item, null);
        }

        TextView credential_name_field = (TextView) convertView.findViewById(R.id.credential_item_name);
        credential_name_field.setText(credential_name);
        if (attrs.isValid()) // Since the convertView gets reused, the TextView might be grey from a previous usage
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

        CredentialDescription cd = credentials.get(credential_idx);
        Attributes attrs = credentialAttributes.get(cd);

        if (attribute_idx == 0) {
            String validDate = DateFormat.getDateInstance().format(attrs.getExpiryDate());
            attribute_name_field.setText("Valid until");
            attribute_value_field.setText(validDate);
            if (attrs.isValid()) {
                attribute_name_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));
                attribute_value_field.setTextColor(convertView.getResources().getColor(R.color.irmadarkgrey));
            } else {
                attribute_name_field.setTextColor(convertView.getResources().getColor(R.color.irmared));
                attribute_value_field.setTextColor(convertView.getResources().getColor(R.color.irmared));
            }
        } else {
            AttributeDescription ad = cd.getAttributes().get(attribute_idx - 1);
            String attribute_name = ad.getName();
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
