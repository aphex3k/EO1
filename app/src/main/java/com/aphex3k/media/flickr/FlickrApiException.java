package com.aphex3k.media.flickr;

/**
 * A Flickr answer that says the call itself failed: {@code stat=fail} with an error code and a
 * message, or an HTTP status that is not a success.
 *
 * <p>Distinct from an {@link java.io.IOException}, which means the wire did not answer and is nobody's
 * asset — the pipeline treats that as a propagating failure rather than as grounds to descend a
 * ladder or skip a photo.
 */
public class FlickrApiException extends Exception {

    /** Flickr's own error code, or -1 when the answer carried none. */
    public final int code;

    public FlickrApiException(int code, String message) {
        super(message);
        this.code = code;
    }
}
