// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.graphics.Bitmap

/**
 * Sala virtual como imagem equiretangular. O desenho em si mora no
 * [RoomTracer] (sem dependencias de Android); aqui so se embrulha o
 * resultado num Bitmap. Leva alguns segundos: chamar fora da thread de UI.
 */
internal object CinemaRoom
{
	const val WIDTH = RoomTracer.WIDTH
	const val HEIGHT = RoomTracer.HEIGHT

	fun render(style: Int): Bitmap
	{
		val pixels = RoomTracer.render(style)
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
		return bitmap
	}
}
