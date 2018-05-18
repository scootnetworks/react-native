/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.modules.network;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.internal.http.CallServerInterceptor;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;

public class ProgressRequestBody extends RequestBody {

  private final RequestBody mRequestBody;
  private final ProgressListener mProgressListener;
  private BufferedSink mBufferedSink;
  private long mContentLength = 0L;

  public ProgressRequestBody(RequestBody requestBody, ProgressListener progressListener) {
    mRequestBody = requestBody;
    mProgressListener = progressListener;
  }

  @Override
  public MediaType contentType() {
    return mRequestBody.contentType();
  }

  @Override
  public long contentLength() throws IOException {
    if (mContentLength == 0) {
      mContentLength = mRequestBody.contentLength();
    }
    return mContentLength;
  }

  /**
   * When using interceptors that use {@link RequestBody#writeTo(BufferedSink)}, this instance will first write to a buffer
   * that is not the network buffer, and it will keep that bufferedSink around. When it comes time to actually do the network
   * request, this method's bufferedSink does not write to the BufferedSink param correctly, which fails the network request.
   *
   * The Happy path behavior that this class was originally created for is when your application does NOT have any
   * interceptors on the {@link OkHttpClient}. To verify the happy path, you will notice that there is only 1 interceptor
   * who actually writes to this sink ({@link CallServerInterceptor} which is the last interceptor in the call chain).
   *
   * To expose the problem with reusing the mBufferedSink variable, simply add an interceptor that calls into this method with
   * its own BufferedSink. This will cause mBufferedSink to be created with the output stream of the sink from that
   * interceptor, and NOT {@link CallServerInterceptor}. So by the time the last {@link CallServerInterceptor} tries to perform
   * the actual network call, this method fails to write to the passed in sink correctly.
   *
   * @param sink
   * @throws IOException
   */
  @Override
  public void writeTo(BufferedSink sink) throws IOException {

    nonCachedWriteTo(sink);

    /*
    if (mBufferedSink == null) {
      mBufferedSink = Okio.buffer(outputStreamSink(sink));
    }

    // contentLength changes for input streams, since we're using inputStream.available(),
    // so get the length before writing to the sink
    contentLength();

    mRequestBody.writeTo(mBufferedSink);
    mBufferedSink.flush();
    */
  }

  private void nonCachedWriteTo(BufferedSink sink) throws IOException {
    BufferedSink progressSink = Okio.buffer(outputStreamSink(sink));

    // contentLength changes for input streams, since we're using inputStream.available(),
    // so get the length before writing to the sink
    contentLength();

    mRequestBody.writeTo(progressSink);
    progressSink.flush();
  }

  private Sink outputStreamSink(BufferedSink sink) {
    return Okio.sink(new CountingOutputStream(sink.outputStream()) {
      @Override
      public void write(byte[] data, int offset, int byteCount) throws IOException {
        super.write(data, offset, byteCount);
        sendProgressUpdate();
      }

      @Override
      public void write(int data) throws IOException {
        super.write(data);
        sendProgressUpdate();
      }

      private void sendProgressUpdate() throws IOException {
        long bytesWritten = getCount();
        long contentLength = contentLength();
        mProgressListener.onProgress(
          bytesWritten, contentLength, bytesWritten == contentLength);
      }
    });
  }
}
