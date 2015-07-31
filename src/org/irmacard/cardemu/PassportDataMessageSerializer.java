package org.irmacard.cardemu;

import android.util.Base64;
import com.google.gson.*;
import org.irmacard.mno.common.PassportDataMessage;
import org.jmrtd.lds.DG14File;
import org.jmrtd.lds.DG15File;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.SODFile;

import java.lang.reflect.Type;

/**
 * Created by fabianbr on 21-7-15.
 */
public class PassportDataMessageSerializer
            implements JsonSerializer<PassportDataMessage> {

        public JsonElement serialize(PassportDataMessage src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();

            String sessionToken = src.getSessionToken();
            String imsi = src.getImsi();
            SODFile sodFile = src.getSodFile();
            DG1File dg1File = src.getDg1File();
            DG14File dg14File = src.getDg14File();
            DG15File dg15File = src.getDg15File();
            byte[] response = src.getResponse();

            obj.addProperty("sessionToken", sessionToken);
            obj.addProperty("imsi", imsi);

            obj.addProperty("sodFile", context.serialize(sodFile.getEncoded()).getAsString());
            obj.addProperty("dg1File", context.serialize(dg1File.getEncoded()).getAsString());
            obj.addProperty("dg14File", context.serialize(dg14File.getEncoded()).getAsString());
            obj.addProperty("dg15File", context.serialize(dg15File.getEncoded()).getAsString());

            obj.addProperty("response", context.serialize(response).getAsString());

            return obj;
        }

}

