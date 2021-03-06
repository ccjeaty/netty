/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_LENGTH_MASK;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_PAYLOAD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.PRIORITY_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTINGS_COMPRESS_DATA;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTINGS_ENABLE_PUSH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTINGS_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTINGS_INITIAL_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTINGS_MAX_CONCURRENT_STREAMS;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.readUnsignedInt;
import static io.netty.handler.codec.http2.Http2Exception.protocolError;
import static io.netty.util.CharsetUtil.UTF_8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;

/**
 * A {@link Http2FrameReader} that supports all frame types defined by the HTTP/2 specification.
 */
public class DefaultHttp2FrameReader implements Http2FrameReader {

    private enum State {
        FRAME_HEADER,
        FRAME_PAYLOAD,
        ERROR
    }

    private final Http2HeadersDecoder headersDecoder;

    private State state = State.FRAME_HEADER;
    private Http2FrameType frameType;
    private int streamId;
    private Http2Flags flags;
    private int payloadLength;
    private HeadersContinuation headersContinuation;

    public DefaultHttp2FrameReader() {
        this(new DefaultHttp2HeadersDecoder());
    }

    public DefaultHttp2FrameReader(Http2HeadersDecoder headersDecoder) {
        this.headersDecoder = headersDecoder;
    }

    @Override
    public void maxHeaderTableSize(int max) {
        headersDecoder.maxHeaderTableSize(max);
    }

    @Override
    public int maxHeaderTableSize() {
        return headersDecoder.maxHeaderTableSize();
    }

    @Override
    public void close() {
        if (headersContinuation != null) {
            headersContinuation.close();
        }
    }

    @Override
    public void readFrame(ChannelHandlerContext ctx, ByteBuf input, Http2FrameObserver observer)
            throws Http2Exception {
        try {
            while (input.isReadable()) {
                switch (state) {
                    case FRAME_HEADER:
                        processHeaderState(input);
                        if (state == State.FRAME_HEADER) {
                            // Wait until the entire header has arrived.
                            return;
                        }

                        // The header is complete, fall into the next case to process the payload.
                        // This is to ensure the proper handling of zero-length payloads. In this
                        // case, we don't want to loop around because there may be no more data
                        // available, causing us to exit the loop. Instead, we just want to perform
                        // the first pass at payload processing now.
                    case FRAME_PAYLOAD:
                        processPayloadState(ctx, input, observer);
                        if (state == State.FRAME_PAYLOAD) {
                            // Wait until the entire payload has arrived.
                            return;
                        }
                        break;
                    case ERROR:
                        input.skipBytes(input.readableBytes());
                        return;
                    default:
                        throw new IllegalStateException("Should never get here");
                }
            }
        } catch (Http2Exception e) {
            state = State.ERROR;
            throw e;
        } catch (RuntimeException e) {
            state = State.ERROR;
            throw e;
        } catch (Error e) {
            state = State.ERROR;
            throw e;
        }
    }

    private void processHeaderState(ByteBuf in) throws Http2Exception {
        if (in.readableBytes() < FRAME_HEADER_LENGTH) {
            // Wait until the entire frame header has been read.
            return;
        }

        // Read the header and prepare the unmarshaller to read the frame.
        payloadLength = in.readUnsignedShort() & FRAME_LENGTH_MASK;
        frameType = Http2FrameType.forTypeCode(in.readUnsignedByte());
        flags = new Http2Flags(in.readUnsignedByte());
        streamId = readUnsignedInt(in);

        switch (frameType) {
            case DATA:
                verifyDataFrame();
                break;
            case HEADERS:
                verifyHeadersFrame();
                break;
            case PRIORITY:
                verifyPriorityFrame();
                break;
            case RST_STREAM:
                verifyRstStreamFrame();
                break;
            case SETTINGS:
                verifySettingsFrame();
                break;
            case PUSH_PROMISE:
                verifyPushPromiseFrame();
                break;
            case PING:
                verifyPingFrame();
                break;
            case GO_AWAY:
                verifyGoAwayFrame();
                break;
            case WINDOW_UPDATE:
                verifyWindowUpdateFrame();
                break;
            case CONTINUATION:
                verifyContinuationFrame();
                break;
            case ALT_SVC:
                verifyAltSvcFrame();
                break;
            case BLOCKED:
                verifyBlockedFrame();
                break;
            default:
                throw protocolError("Unsupported frame type: %s", frameType);
        }

        // Start reading the payload for the frame.
        state = State.FRAME_PAYLOAD;
    }

    private void
            processPayloadState(ChannelHandlerContext ctx, ByteBuf in, Http2FrameObserver observer)
                    throws Http2Exception {
        if (in.readableBytes() < payloadLength) {
            // Wait until the entire payload has been read.
            return;
        }

        // Get a view of the buffer for the size of the payload.
        ByteBuf payload = in.readSlice(payloadLength);

        // Read the payload and fire the frame event to the observer.
        switch (frameType) {
            case DATA:
                readDataFrame(ctx, payload, observer);
                break;
            case HEADERS:
                readHeadersFrame(ctx, payload, observer);
                break;
            case PRIORITY:
                readPriorityFrame(ctx, payload, observer);
                break;
            case RST_STREAM:
                readRstStreamFrame(ctx, payload, observer);
                break;
            case SETTINGS:
                readSettingsFrame(ctx, payload, observer);
                break;
            case PUSH_PROMISE:
                readPushPromiseFrame(ctx, payload, observer);
                break;
            case PING:
                readPingFrame(ctx, payload, observer);
                break;
            case GO_AWAY:
                readGoAwayFrame(ctx, payload, observer);
                break;
            case WINDOW_UPDATE:
                readWindowUpdateFrame(ctx, payload, observer);
                break;
            case CONTINUATION:
                readContinuationFrame(payload, observer);
                break;
            case ALT_SVC:
                readAltSvcFrame(ctx, payload, observer);
                break;
            case BLOCKED:
                observer.onBlockedRead(ctx, streamId);
                break;
            default:
                // Should never happen.
                throw protocolError("Unsupported frame type: %s", frameType);
        }

        // Go back to reading the next frame header.
        state = State.FRAME_HEADER;
    }

    private void verifyDataFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyPayloadLength(payloadLength);

        if (!flags.isPaddingLengthValid()) {
            throw protocolError("Pad high is set but pad low is not");
        }
        if (payloadLength < flags.getNumPaddingLengthBytes()) {
            throw protocolError("Frame length %d too small.", payloadLength);
        }
    }

    private void verifyHeadersFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyPayloadLength(payloadLength);

        int lengthWithoutPriority = flags.getNumPaddingLengthBytes();
        if (lengthWithoutPriority < 0) {
            throw protocolError("Frame length too small." + payloadLength);
        }

        if (!flags.isPaddingLengthValid()) {
            throw protocolError("Pad high is set but pad low is not");
        }

        if (lengthWithoutPriority < flags.getNumPaddingLengthBytes()) {
            throw protocolError("Frame length %d too small for padding.", payloadLength);
        }
    }

    private void verifyPriorityFrame() throws Http2Exception {
        verifyNotProcessingHeaders();

        if (payloadLength != PRIORITY_ENTRY_LENGTH) {
            throw protocolError("Invalid frame length %d.", payloadLength);
        }
    }

    private void verifyRstStreamFrame() throws Http2Exception {
        verifyNotProcessingHeaders();

        if (payloadLength != INT_FIELD_LENGTH) {
            throw protocolError("Invalid frame length %d.", payloadLength);
        }
    }

    private void verifySettingsFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyPayloadLength(payloadLength);
        if (streamId != 0) {
            throw protocolError("A stream ID must be zero.");
        }
        if (flags.ack() && payloadLength > 0) {
            throw protocolError("Ack settings frame must have an empty payload.");
        }
        if (payloadLength % SETTING_ENTRY_LENGTH > 0) {
            throw protocolError("Frame length %d invalid.", payloadLength);
        }
    }

    private void verifyPushPromiseFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyPayloadLength(payloadLength);

        if (!flags.isPaddingLengthValid()) {
            throw protocolError("Pad high is set but pad low is not");
        }

        // Subtract the length of the promised stream ID field, to determine the length of the
        // rest of the payload (header block fragment + payload).
        int lengthWithoutPromisedId = payloadLength - INT_FIELD_LENGTH;
        if (lengthWithoutPromisedId < flags.getNumPaddingLengthBytes()) {
            throw protocolError("Frame length %d too small for padding.", payloadLength);
        }
    }

    private void verifyPingFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        if (streamId != 0) {
            throw protocolError("A stream ID must be zero.");
        }
        if (payloadLength != 8) {
            throw protocolError("Frame length %d incorrect size for ping.", payloadLength);
        }
    }

    private void verifyGoAwayFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyPayloadLength(payloadLength);

        if (streamId != 0) {
            throw protocolError("A stream ID must be zero.");
        }
        if (payloadLength < 8) {
            throw protocolError("Frame length %d too small.", payloadLength);
        }
    }

    private void verifyWindowUpdateFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyStreamOrConnectionId(streamId, "Stream ID");

        if (payloadLength != INT_FIELD_LENGTH) {
            throw protocolError("Invalid frame length %d.", payloadLength);
        }
    }

    private void verifyContinuationFrame() throws Http2Exception {
        verifyPayloadLength(payloadLength);

        if (headersContinuation == null) {
            throw protocolError("Received %s frame but not currently processing headers.",
                    frameType);
        }

        if (streamId != headersContinuation.getStreamId()) {
            throw protocolError("Continuation stream ID does not match pending headers. "
                    + "Expected %d, but received %d.", headersContinuation.getStreamId(), streamId);
        }

        if (!flags.isPaddingLengthValid()) {
            throw protocolError("Pad high is set but pad low is not");
        }

        if (payloadLength < flags.getNumPaddingLengthBytes()) {
            throw protocolError("Frame length %d too small for padding.", payloadLength);
        }
    }

    private void verifyAltSvcFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyStreamOrConnectionId(streamId, "Stream ID");
        verifyPayloadLength(payloadLength);

        if (payloadLength < 8) {
            throw protocolError("Frame length too small." + payloadLength);
        }
    }

    private void verifyBlockedFrame() throws Http2Exception {
        verifyNotProcessingHeaders();
        verifyStreamOrConnectionId(streamId, "Stream ID");

        if (payloadLength != 0) {
            throw protocolError("Invalid frame length %d.", payloadLength);
        }
    }

    private void readDataFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        int dataPadding = flags.readPaddingLength(payload);

        // Determine how much data there is to read by removing the trailing
        // padding.
        int dataLength = payload.readableBytes() - dataPadding;
        if (dataLength < 0) {
            throw protocolError("Frame payload too small for padding.");
        }

        ByteBuf data = payload.readSlice(dataLength);
        observer.onDataRead(ctx, streamId, data, dataPadding, flags.endOfStream(),
                flags.endOfSegment(), flags.compressed());
        payload.skipBytes(payload.readableBytes());
    }

    private void readHeadersFrame(final ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        final int headersStreamId = streamId;
        final Http2Flags headersFlags = flags;
        int padding = flags.readPaddingLength(payload);

        // The callback that is invoked is different depending on whether priority information
        // is present in the headers frame.
        if (flags.priorityPresent()) {
            long word1 = payload.readUnsignedInt();
            final boolean exclusive = (word1 & 0x80000000L) > 0;
            final int streamDependency = (int) (word1 & 0x7FFFFFFFL);
            final short weight = (short) (payload.readUnsignedByte() + 1);
            final ByteBuf fragment = payload.readSlice(payload.readableBytes() - padding);

            // Create a handler that invokes the observer when the header block is complete.
            headersContinuation = new HeadersContinuation() {
                @Override
                public int getStreamId() {
                    return headersStreamId;
                }

                @Override
                public void processFragment(boolean endOfHeaders, ByteBuf fragment, int padding,
                        Http2FrameObserver observer) throws Http2Exception {
                    builder().addFragment(fragment, ctx.alloc(), endOfHeaders);
                    if (endOfHeaders) {
                        Http2Headers headers = builder().buildHeaders();
                        observer.onHeadersRead(ctx, headersStreamId, headers, streamDependency,
                                weight, exclusive, padding, headersFlags.endOfStream(),
                                headersFlags.endOfSegment());
                        close();
                    }
                }
            };

            // Process the initial fragment, invoking the observer's callback if end of headers.
            headersContinuation.processFragment(flags.endOfHeaders(), fragment, padding, observer);
            return;
        }

        // The priority fields are not present in the frame. Prepare a continuation that invokes
        // the observer callback without priority information.
        headersContinuation = new HeadersContinuation() {
            @Override
            public int getStreamId() {
                return headersStreamId;
            }

            @Override
            public void processFragment(boolean endOfHeaders, ByteBuf fragment, int padding,
                    Http2FrameObserver observer) throws Http2Exception {
                builder().addFragment(fragment, ctx.alloc(), endOfHeaders);
                if (endOfHeaders) {
                    Http2Headers headers = builder().buildHeaders();
                    observer.onHeadersRead(ctx, headersStreamId, headers, padding,
                            headersFlags.endOfStream(), headersFlags.endOfSegment());
                    close();
                }
            }
        };

        // Process the initial fragment, invoking the observer's callback if end of headers.
        final ByteBuf fragment = payload.readSlice(payload.readableBytes() - padding);
        headersContinuation.processFragment(flags.endOfHeaders(), fragment, padding, observer);
    }

    private void readPriorityFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        long word1 = payload.readUnsignedInt();
        boolean exclusive = (word1 & 0x80000000L) > 0;
        int streamDependency = (int) (word1 & 0x7FFFFFFFL);
        short weight = (short) (payload.readUnsignedByte() + 1);
        observer.onPriorityRead(ctx, streamId, streamDependency, weight, exclusive);
    }

    private void readRstStreamFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        long errorCode = payload.readUnsignedInt();
        observer.onRstStreamRead(ctx, streamId, errorCode);
    }

    private void readSettingsFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        if (flags.ack()) {
            observer.onSettingsAckRead(ctx);
        } else {
            int numSettings = payloadLength / SETTING_ENTRY_LENGTH;
            Http2Settings settings = new Http2Settings();
            for (int index = 0; index < numSettings; ++index) {
                short id = payload.readUnsignedByte();
                long value = payload.readUnsignedInt();
                switch (id) {
                    case SETTINGS_HEADER_TABLE_SIZE:
                        if (value < 0 || value > Integer.MAX_VALUE) {
                            throw protocolError("Invalid value for HEADER_TABLE_SIZE: %d", value);
                        }
                        settings.maxHeaderTableSize((int) value);
                        break;
                    case SETTINGS_COMPRESS_DATA:
                        if (value != 0 && value != 1) {
                            throw protocolError("Invalid value for COMPRESS_DATA: %d", value);
                        }
                        settings.allowCompressedData(value == 1);
                        break;
                    case SETTINGS_ENABLE_PUSH:
                        if (value != 0 && value != 1) {
                            throw protocolError("Invalid value for ENABLE_PUSH: %d", value);
                        }
                        settings.pushEnabled(value == 1);
                        break;
                    case SETTINGS_INITIAL_WINDOW_SIZE:
                        if (value < 0 || value > Integer.MAX_VALUE) {
                            throw protocolError("Invalid value for INITIAL_WINDOW_SIZE: %d", value);
                        }
                        settings.initialWindowSize((int) value);
                        break;
                    case SETTINGS_MAX_CONCURRENT_STREAMS:
                        if (value < 0 || value > Integer.MAX_VALUE) {
                            throw protocolError("Invalid value for MAX_CONCURRENT_STREAMS: %d",
                                    value);
                        }
                        settings.maxConcurrentStreams((int) value);
                        break;
                    default:
                        throw protocolError("Unsupport setting: %d", id);
                }
            }
            observer.onSettingsRead(ctx, settings);
        }
    }

    private void readPushPromiseFrame(final ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        final int pushPromiseStreamId = streamId;
        int padding = flags.readPaddingLength(payload);
        final int promisedStreamId = readUnsignedInt(payload);

        // Create a handler that invokes the observer when the header block is complete.
        headersContinuation = new HeadersContinuation() {
            @Override
            public int getStreamId() {
                return pushPromiseStreamId;
            }

            @Override
            public void processFragment(boolean endOfHeaders, ByteBuf fragment, int padding,
                    Http2FrameObserver observer) throws Http2Exception {
                builder().addFragment(fragment, ctx.alloc(), endOfHeaders);
                if (endOfHeaders) {
                    Http2Headers headers = builder().buildHeaders();
                    observer.onPushPromiseRead(ctx, pushPromiseStreamId, promisedStreamId, headers,
                            padding);
                    close();
                }
            }
        };

        // Process the initial fragment, invoking the observer's callback if end of headers.
        final ByteBuf fragment = payload.readSlice(payload.readableBytes() - padding);
        headersContinuation.processFragment(flags.endOfHeaders(), fragment, padding, observer);
    }

    private void readPingFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        ByteBuf data = payload.readSlice(payload.readableBytes());
        if (flags.ack()) {
            observer.onPingAckRead(ctx, data);
        } else {
            observer.onPingRead(ctx, data);
        }
    }

    private static void readGoAwayFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        int lastStreamId = readUnsignedInt(payload);
        long errorCode = payload.readUnsignedInt();
        ByteBuf debugData = payload.readSlice(payload.readableBytes());
        observer.onGoAwayRead(ctx, lastStreamId, errorCode, debugData);
    }

    private void readWindowUpdateFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        int windowSizeIncrement = readUnsignedInt(payload);
        observer.onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
    }

    private void readContinuationFrame(ByteBuf payload, Http2FrameObserver observer)
            throws Http2Exception {
        int padding = flags.readPaddingLength(payload);

        // Process the initial fragment, invoking the observer's callback if end of headers.
        final ByteBuf continuationFragment = payload.readSlice(payload.readableBytes() - padding);
        headersContinuation.processFragment(flags.endOfHeaders(), continuationFragment, padding,
                observer);
    }

    private void readAltSvcFrame(ChannelHandlerContext ctx, ByteBuf payload,
            Http2FrameObserver observer) throws Http2Exception {
        long maxAge = payload.readUnsignedInt();
        int port = payload.readUnsignedShort();
        payload.skipBytes(1);
        short protocolIdLength = payload.readUnsignedByte();
        ByteBuf protocolId = payload.readSlice(protocolIdLength);
        short hostLength = payload.readUnsignedByte();
        String host = payload.toString(payload.readerIndex(), hostLength, UTF_8);
        payload.skipBytes(hostLength);
        String origin = null;
        if (payload.isReadable()) {
            origin = payload.toString(UTF_8);
            payload.skipBytes(payload.readableBytes());
        }
        observer.onAltSvcRead(ctx, streamId, maxAge, port, protocolId, host, origin);
    }

    /**
     * Base class for processing of HEADERS and PUSH_PROMISE header blocks that potentially span
     * multiple frames. The implementation of this interface will perform the final callback to the
     * {@link Http2FrameObserver} once the end of headers is reached.
     */
    private abstract class HeadersContinuation {
        private final HeadersBuilder builder = new HeadersBuilder();

        /**
         * Returns the stream for which headers are currently being processed.
         */
        abstract int getStreamId();

        /**
         * Processes the next fragment for the current header block.
         *
         * @param endOfHeaders whether the fragment is the last in the header block.
         * @param fragment the fragment of the header block to be added.
         * @param padding the amount of padding to be supplied to the {@link Http2FrameObserver}
         *            callback.
         * @param observer the observer to be notified if the header block is completed.
         */
        abstract void processFragment(boolean endOfHeaders, ByteBuf fragment, int padding,
                Http2FrameObserver observer) throws Http2Exception;

        final HeadersBuilder builder() {
            return builder;
        }

        /**
         * Free any allocated resources.
         */
        final void close() {
            builder.close();
        }
    }

    /**
     * Utility class to help with construction of the headers block that may potentially span
     * multiple frames.
     */
    private class HeadersBuilder {
        private ByteBuf headerBlock;

        /**
         * Adds a fragment to the block.
         *
         * @param fragment the fragment of the headers block to be added.
         * @param alloc allocator for new blocks if needed.
         * @param endOfHeaders flag indicating whether the current frame is the end of the headers.
         *            This is used for an optimization for when the first fragment is the full
         *            block. In that case, the buffer is used directly without copying.
         */
        final void addFragment(ByteBuf fragment, ByteBufAllocator alloc, boolean endOfHeaders) {
            if (headerBlock == null) {
                if (endOfHeaders) {
                    // Optimization - don't bother copying, just use the buffer as-is. Need
                    // to retain since we release when the header block is built.
                    headerBlock = fragment.retain();
                } else {
                    headerBlock = alloc.buffer(fragment.readableBytes());
                    headerBlock.writeBytes(fragment);
                }
                return;
            }
            if (headerBlock.isWritable(fragment.readableBytes())) {
                // The buffer can hold the requeste bytes, just write it directly.
                headerBlock.writeBytes(fragment);
            } else {
                // Allocate a new buffer that is big enough to hold the entire header block so far.
                ByteBuf buf = alloc.buffer(headerBlock.readableBytes() + fragment.readableBytes());
                buf.writeBytes(headerBlock);
                buf.writeBytes(fragment);
                headerBlock.release();
                headerBlock = buf;
            }
        }

        /**
         * Builds the headers from the completed headers block. After this is called, this builder
         * should not be called again.
         */
        Http2Headers buildHeaders() throws Http2Exception {
            try {
                return headersDecoder.decodeHeaders(headerBlock);
            } finally {
                close();
            }
        }

        /**
         * Closes this builder and frees any resources.
         */
        void close() {
            if (headerBlock != null) {
                headerBlock.release();
                headerBlock = null;
            }

            // Clear the member variable pointing at this instance.
            headersContinuation = null;
        }
    }

    private void verifyNotProcessingHeaders() throws Http2Exception {
        if (headersContinuation != null) {
            throw protocolError("Received frame of type %s while processing headers.", frameType);
        }
    }

    private static void verifyStreamOrConnectionId(int streamId, String argumentName)
            throws Http2Exception {
        if (streamId < 0) {
            throw protocolError("%s must be >= 0", argumentName);
        }
    }

    private static void verifyPayloadLength(int payloadLength) throws Http2Exception {
        if (payloadLength > MAX_FRAME_PAYLOAD_LENGTH) {
            throw protocolError("Total payload length %d exceeds max frame length.", payloadLength);
        }
    }
}
