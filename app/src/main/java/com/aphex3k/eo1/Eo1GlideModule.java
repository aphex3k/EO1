package com.aphex3k.eo1;

import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Empty AppGlideModule so the Glide annotation processor generates
 * GeneratedAppGlideModule and LibraryGlideModules are not silently ignored.
 * No custom configuration is needed: Glide here only loads local files.
 */
@GlideModule
public final class Eo1GlideModule extends AppGlideModule {
}
