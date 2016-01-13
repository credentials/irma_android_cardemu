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

package org.irmacard.cardemu.selfenrol;

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
            if (dg14File != null)
                obj.addProperty("dg14File", context.serialize(dg14File.getEncoded()).getAsString());
            obj.addProperty("dg15File", context.serialize(dg15File.getEncoded()).getAsString());

            obj.addProperty("response", context.serialize(response).getAsString());

            return obj;
        }

}

