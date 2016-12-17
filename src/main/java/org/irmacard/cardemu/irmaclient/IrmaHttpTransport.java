package org.irmacard.cardemu.irmaclient;

import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;

import java.lang.reflect.Type;

public class IrmaHttpTransport implements IrmaTransport {
    private String server;
    private JsonHttpClient client;

    public IrmaHttpTransport(String server) {
        this.server = server;
        this.client = new JsonHttpClient(GsonUtil.getGson());
    }

    public <T> void post(Type type, String url, Object object, HttpResultHandler<T> handler) {
        client.post(type, server + url, object, handler);
    }

    public <T> void get(Type type, String url, HttpResultHandler<T> handler) {
        client.get(type, server + url, handler);
    }

    public void delete() {
        client.delete(server, new HttpResultHandler<Object>() {
            @Override public void onSuccess(Object result) {}
            @Override public void onError(HttpClientException e) {
                e.printStackTrace();
            }
        });
    }
}
