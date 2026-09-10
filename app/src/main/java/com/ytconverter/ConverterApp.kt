package com.ytconverter

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class ConverterApp : Application(), SingletonImageLoader.Factory {

    /**
     * Coil 3 keeps networking in a separate artifact, so the fetcher has to be
     * registered explicitly. Thumbnails then cache to disk automatically.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
