package com.aphex3k.media;

/**
 * Asset media type used across the rotation pipeline.
 * Replaces {@code com.aphex3k.immichApi.ImmichType} at the pipeline boundary;
 * backends translate their native types into this enum.
 */
public enum MediaType {
    IMAGE,
    VIDEO,
    /**
     * A web page: rendered by a browser view rather than a decoder, so it is never downloaded,
     * cached or codec-checked. Only {@code com.aphex3k.media.web.WebsiteBackend} produces it.
     */
    WEB
}
