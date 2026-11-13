// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Sala virtual como imagem equiretangular.
 *
 * Se existir uma foto panoramica em `assets/rooms/<nome>.jpg` (2:1, com o
 * centro da imagem na direcao da tela), ela e usada como esta. Sem o arquivo,
 * cai no desenho procedural do [RoomTracer], que nao depende de Android e leva
 * alguns segundos. Em qualquer caso, chamar fora da thread de UI.
 */
internal object CinemaRoom
{
	private const val TAG = "P5M-Room"

	/** Um arquivo por estilo, na ordem de [ScreenPrefs.ROOM_NAMES]. */
	private val ASSETS = arrayOf("rooms/cinema.jpg", "rooms/horror.jpg")

	fun render(context: Context, style: Int): Bitmap
	{
		fromAsset(context, style)?.let { return it }
		val pixels = RoomTracer.render(style)
		val bitmap = Bitmap.createBitmap(RoomTracer.WIDTH, RoomTracer.HEIGHT, Bitmap.Config.ARGB_8888)
		bitmap.setPixels(pixels, 0, RoomTracer.WIDTH, 0, 0, RoomTracer.WIDTH, RoomTracer.HEIGHT)
		return bitmap
	}

	/**
	 * Decodifica a foto em ARGB_8888. Uma panoramica de 8192x4096 ocupa 128 MB
	 * decodificada; se faltar memoria, tenta de novo na metade do tamanho, que
	 * ainda e bem mais nitida que o desenho procedural.
	 */
	private fun fromAsset(context: Context, style: Int): Bitmap?
	{
		val name = ASSETS.getOrNull(style) ?: return null
		for(sample in intArrayOf(1, 2))
		{
			try
			{
				val options = BitmapFactory.Options().apply {
					inPreferredConfig = Bitmap.Config.ARGB_8888
					inScaled = false
					inSampleSize = sample
				}
				val bitmap = context.assets.open(name).use { BitmapFactory.decodeStream(it, null, options) }
						?: return null
				if(bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.width < 2 * bitmap.height - 2
						|| bitmap.width > 2 * bitmap.height + 2)
				{
					Log.w(TAG, "$name ignored: ${bitmap.width}x${bitmap.height} ${bitmap.config}")
					bitmap.recycle()
					return null
				}
				Log.i(TAG, "$name loaded: ${bitmap.width}x${bitmap.height} (sample $sample)")
				return bitmap
			}
			catch(e: java.io.FileNotFoundException)
			{
				return null
			}
			catch(e: OutOfMemoryError)
			{
				Log.w(TAG, "Out of memory decoding $name at sample $sample")
			}
			catch(e: java.io.IOException)
			{
				Log.w(TAG, "Could not read $name: ${e.message}")
				return null
			}
		}
		return null
	}
}
