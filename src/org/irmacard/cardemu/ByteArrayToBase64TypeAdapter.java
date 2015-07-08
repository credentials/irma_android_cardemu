package org.irmacard.cardemu;

import java.lang.reflect.Type;

import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * A (de)serializer for Gson that serializes byte arrays to and from
 * Base64-encoded strings. From https://gist.github.com/orip/3635246
 */
public class ByteArrayToBase64TypeAdapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {
	public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		return Base64.decode(json.getAsString(), Base64.NO_WRAP);
	}

	public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
		return new JsonPrimitive(Base64.encodeToString(src, Base64.NO_WRAP));
	}
}
