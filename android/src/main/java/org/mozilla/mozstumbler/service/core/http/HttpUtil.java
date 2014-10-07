/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.mozstumbler.service.core.http;

import android.os.Build;

import org.mozilla.mozstumbler.client.ClientPrefs;
import org.mozilla.mozstumbler.service.AppGlobals;
import org.mozilla.mozstumbler.service.core.logging.Log;
import org.mozilla.mozstumbler.service.utils.Zipper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HttpUtil implements IHttpUtil {

    private static final String LOG_TAG = AppGlobals.LOG_PREFIX + HttpUtil.class.getSimpleName();
    private static final String USER_AGENT_HEADER = "User-Agent";
    private final String userAgent;

    public HttpUtil() {
        this(ClientPrefs.getInstance().getUserAgent());
    }
    public HttpUtil(String ua){
        userAgent = ua;
    };

    private URLConnection openConnectionWithProxy(URL url) throws IOException {
        Proxy proxy = Proxy.NO_PROXY;

        ProxySelector proxySelector = ProxySelector.getDefault();
        if (proxySelector != null) {
            URI uri;
            try {
                uri = url.toURI();
            } catch (URISyntaxException e) {
                IOException ioe = new IOException(url.toString());
                ioe.initCause(e);
                throw ioe;
            }

            List<Proxy> proxies = proxySelector.select(uri);
            if (proxies != null && !proxies.isEmpty()) {
                proxy = proxies.get(0);
            }
        }

        return url.openConnection(proxy);
    }

    public String getUrlAsString(URL url) throws IOException {
        InputStream stream = null;
        try {
            URLConnection connection = openConnectionWithProxy(url);
            stream = connection.getInputStream();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(stream));
                return reader.readLine();
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    @Override
    public String getUrlAsString(String url) throws IOException {
        return getUrlAsString(new URL(url));
    }

    @Override
    public InputStream getUrlAsStream(String url) throws IOException {
        return new URL(url).openStream();
    }


    public File getUrlAsFile(URL url, File file) throws IOException {
        final int bufferLength = 8192;
        final byte[] buffer = new byte[bufferLength];
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            URLConnection connection = openConnectionWithProxy(url);
            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(file);
            for (;;) {
                int readLength = inputStream.read(buffer, 0, bufferLength);
                if (readLength == -1) {
                    return file;
                }
                outputStream.write(buffer, 0, readLength);
            }
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    @Override
    public IResponse post(String urlString, byte[] data, Map<String, String> headers, boolean precompressed, MLS mls) {

        URL url = null;
        HttpURLConnection httpURLConnection = null;

        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }

        if (data == null) {
            throw new IllegalArgumentException("Data must be not null");
        }

        if (headers == null) {
            headers = new HashMap<String, String>();
        }


        try {
            httpURLConnection = (HttpURLConnection) url.openConnection();

            // HttpURLConnection and Java are braindead.
            // http://stackoverflow.com/questions/8587913/what-exactly-does-urlconnection-setdooutput-affect
            httpURLConnection.setDoOutput(true);
            httpURLConnection.setRequestMethod("POST");

        } catch (IOException e) {
            Log.e(LOG_TAG, "Couldn't open a connection: " + e);
            return null;
        }

        httpURLConnection.setDoOutput(true);
        httpURLConnection.setRequestProperty(USER_AGENT_HEADER, userAgent);
        httpURLConnection.setRequestProperty("Content-Type", "application/json");

        // Workaround for a bug in Android mHttpURLConnection. When the library
        // reuses a stale connection, the connection may fail with an EOFException
        // http://stackoverflow.com/questions/15411213/android-httpsurlconnection-eofexception/17791819#17791819
        if (Build.VERSION.SDK_INT > 13 && Build.VERSION.SDK_INT < 19) {
            httpURLConnection.setRequestProperty("Connection", "Close");
        }

        // Set headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpURLConnection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        byte[] wire_data = data;
        if (!precompressed) {
            wire_data = Zipper.zipData(data);
            if (wire_data != null) {
                httpURLConnection.setRequestProperty("Content-Encoding", "gzip");
            } else {
                Log.w(LOG_TAG, "Couldn't compress data, falling back to raw data.");
                wire_data = data;
            }
        } else {
            httpURLConnection.setRequestProperty("Content-Encoding", "gzip");
        }

        httpURLConnection.setFixedLengthStreamingMode(wire_data.length);
        try {
            OutputStream out = new BufferedOutputStream(httpURLConnection.getOutputStream());
            out.write(wire_data);
            out.flush();

            return new HTTPResponse(httpURLConnection.getResponseCode(),
                    getContentBody(httpURLConnection),
                    wire_data.length);
        } catch (IOException e) {
            Log.i(LOG_TAG, "post error:" + e.toString() + " Data:" + data.toString());
        } finally {
            httpURLConnection.disconnect();
        }
        return null;
    }

    private String getContentBody(HttpURLConnection httpURLConnection) throws IOException {
        String contentBody;
        InputStream in = new BufferedInputStream(httpURLConnection.getInputStream());
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        String line;
        StringBuilder total = new StringBuilder(in.available());
        while ((line = r.readLine()) != null) {
            total.append(line);
        }
        r.close();
        in.close();
        contentBody = total.toString();
        return contentBody;
    }
}
