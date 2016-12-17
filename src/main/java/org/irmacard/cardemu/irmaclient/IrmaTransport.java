package org.irmacard.cardemu.irmaclient;

import org.irmacard.cardemu.httpclient.HttpResultHandler;

import java.lang.reflect.Type;

interface IrmaTransport {
    <T> void post(Type type, String url, Object object, HttpResultHandler<T> handler);
    <T> void get(Type type, String url, HttpResultHandler<T> handler);
    void delete();
}
