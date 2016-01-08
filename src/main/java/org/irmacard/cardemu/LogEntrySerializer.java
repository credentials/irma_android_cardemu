package org.irmacard.cardemu;

import com.google.gson.*;
import org.irmacard.credentials.util.log.IssueLogEntry;
import org.irmacard.credentials.util.log.LogEntry;
import org.irmacard.credentials.util.log.RemoveLogEntry;
import org.irmacard.credentials.util.log.VerifyLogEntry;

import java.lang.reflect.Type;

public class LogEntrySerializer implements JsonSerializer<LogEntry>, JsonDeserializer<LogEntry> {
	@Override
	public JsonElement serialize(LogEntry src, Type typeOfSrc, JsonSerializationContext context) {
		JsonObject o = new JsonObject();

		if (src instanceof VerifyLogEntry) {
			o.addProperty("type", "verification");
			o.add("value", context.serialize((VerifyLogEntry) src));
		}
		else if (src instanceof IssueLogEntry) {
			o.addProperty("type", "issue");
			o.add("value", context.serialize((IssueLogEntry) src));
		}
		else if (src instanceof RemoveLogEntry) {
			o.addProperty("type", "remove");
			o.add("value", context.serialize((RemoveLogEntry) src));
		}

		return o;
	}

	@Override
	public LogEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		JsonObject o = json.getAsJsonObject();
		switch (o.get("type").getAsString()) {
			case "verification":
				return context.deserialize(o.get("value"), VerifyLogEntry.class);
			case "issue":
				return context.deserialize(o.get("value"), IssueLogEntry.class);
			case "remove":
				return context.deserialize(o.get("value"), RemoveLogEntry.class);
			default:
				return null;
		}
	}
}
