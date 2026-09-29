package com.aphex3k.media;

/**
 * Asset media type used across the rotation pipeline.
 * Replaces {@code com.aphex3k.immichApi.ImmichType} at the pipeline boundary;
 * backends translate their native types into this enum.
 */
public enum MediaType {
    IMAGE,
    VIDEO
}
