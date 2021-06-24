/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.http.internal;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HTTP;
import org.gradle.caching.BuildCacheEntryFileReference;
import org.gradle.caching.BuildCacheEntryReader;
import org.gradle.caching.BuildCacheEntryWriter;
import org.gradle.caching.BuildCacheException;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.resource.transport.http.HttpAsyncClientHelper;
import org.gradle.internal.resource.transport.http.HttpRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Build cache implementation that delegates to a service accessible via HTTP.
 */
public class HttpBuildCacheService implements BuildCacheService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpBuildCacheService.class);
    static final String BUILD_CACHE_CONTENT_TYPE = "application/vnd.gradle.build-cache-artifact.v1";
    public static final ContentType CONTENT_TYPE = ContentType.create(BUILD_CACHE_CONTENT_TYPE);

    // Copied from HTTP clients DefaultHttpRequestRetryHandler
    public static final List<Class<? extends IOException>> NON_RETRYABLE_EXCEPTIONS = Arrays.asList(
        InterruptedIOException.class,
        UnknownHostException.class,
        ConnectException.class,
        SSLException.class
    );
    public static final int RETRIES = 3;

    private final URI root;
    private final HttpAsyncClientHelper httpClientHelper;
    private final HttpBuildCacheRequestCustomizer requestCustomizer;
    private final boolean useExpectContinue;

    public HttpBuildCacheService(HttpAsyncClientHelper httpClientHelper, URI url, HttpBuildCacheRequestCustomizer requestCustomizer, boolean useExpectContinue) {
        this.requestCustomizer = requestCustomizer;
        this.useExpectContinue = useExpectContinue;
        if (!url.getPath().endsWith("/")) {
            throw new IllegalArgumentException("HTTP cache root URI must end with '/'");
        }
        this.root = url;
        this.httpClientHelper = httpClientHelper;
    }

    @Override
    public boolean load(BuildCacheKey key, BuildCacheEntryReader reader) throws BuildCacheException {
        final URI uri = root.resolve("./" + key.getHashCode());

        HttpGet httpGet = new HttpGet(uri);
        httpGet.addHeader(HttpHeaders.ACCEPT, CONTENT_TYPE.getMimeType() + ", */*");
        requestCustomizer.customize(httpGet);
        HttpAsyncRequestProducer request = HttpAsyncMethods.create(httpGet);
        HttpAsyncResponseConsumer<HttpResponse> responseConsumer = new LoadResponseConsumer(reader);

        HttpResponse response = withRetries(3, () -> httpClientHelper.request(request, responseConsumer));

        StatusLine statusLine = response.getStatusLine();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Response for GET {}: {}", safeUri(uri), statusLine);
        }

        int statusCode = statusLine.getStatusCode();
        if (isHttpSuccess(statusCode)) {
            return true;
        } else if (statusCode == HttpStatus.SC_NOT_FOUND) {
            return false;
        } else {
            throw new BuildCacheException(String.format("Loading entry from '%s' response status %d: %s", safeUri(uri), statusCode, statusLine.getReasonPhrase()));
        }
    }

    @Override
    public StoreOutcome maybeStore(BuildCacheKey key, BuildCacheEntryWriter writer) throws BuildCacheException {
        final URI uri = root.resolve(key.getHashCode());
        try (BuildCacheEntryFileReference fileReference = writer.openFileReference()) {
            HttpAsyncRequestProducer requestProducer = new PatchedBaseZeroCopyRequestProducer(uri, fileReference.getFile().toFile(), CONTENT_TYPE) {
                @Override
                protected HttpEntityEnclosingRequest createRequest(URI requestURI, HttpEntity entity) {
                    final HttpPut request = new HttpPut(requestURI);
                    request.setEntity(entity);
                    if (useExpectContinue) {
                        request.setHeader(HTTP.EXPECT_DIRECTIVE, HTTP.EXPECT_CONTINUE);
                    }
                    requestCustomizer.customize(request);
                    return request;
                }
            };

            HttpResponse response = withRetries(RETRIES, () -> httpClientHelper.request(requestProducer, new StoreResponseConsumer()));
            StatusLine statusLine = response.getStatusLine();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Response for PUT {}: {}", safeUri(uri), statusLine);
            }
            int statusCode = statusLine.getStatusCode();
            if (isHttpSuccess(statusCode)) {
                return StoreOutcome.STORED;
            } else if (statusCode == HttpStatus.SC_REQUEST_TOO_LONG) {
                return StoreOutcome.NOT_STORED;
            } else {
                throw new BuildCacheException(String.format("Storing entry at '%s' response status %d: %s", safeUri(uri), statusCode, statusLine.getReasonPhrase()));
            }
        }
    }

    private HttpResponse withRetries(int retriesLeft, Supplier<? extends HttpResponse> work) {
        try {
            return work.get();
        } catch (HttpRequestException e) {
            if (retriesLeft == 0) {
                throw e;
            }
            Throwable cause = e.getCause();
            for (Class<? extends IOException> nonRetryableException : NON_RETRYABLE_EXCEPTIONS) {
                if (nonRetryableException.isInstance(cause)) {
                    throw e;
                }
            }

            return withRetries(retriesLeft - 1, work);
        }
    }

    static boolean isHttpSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public void close() throws IOException {
        httpClientHelper.close();
    }

    /**
     * Create a safe URI from the given one by stripping out user info.
     *
     * @param uri Original URI
     * @return a new URI with no user info
     */
    private static URI safeUri(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}
