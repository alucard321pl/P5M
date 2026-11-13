// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Sala de cinema desenhada por codigo: o fundo 360 que substitui o passthrough.
 *
 * Nao ha arquivo de imagem nem modelo 3D. A sala e uma caixa com alguns
 * blocos dentro (degraus, encostos das poltronas), e cada pixel da imagem
 * equiretangular e um raio lancado do olho: acha a superficie mais proxima,
 * pinta pelo material e acende com uma conta simples de luz pontual — a luz
 * da propria tela, os arandelas das paredes e os pontos do teto. Sem sombras.
 * Sao uns milhoes de raios, feitos uma vez em segundo plano; o resultado sobe
 * como textura e nao muda mais.
 *
 * Eixos como no OpenXR: x para a direita, y para cima, a tela fica em -z. O
 * olho esta na origem, 1,15 m acima do piso da sua fileira (altura de quem
 * esta sentado). O centro da imagem aponta para -z, como a camada equiretangular
 * espera.
 *
 * A abertura da parede da frente (retangulo preto entre as cortinas) tem o
 * tamanho que o preset de cinema da tela ocupa visto da distancia da parede,
 * com uma folga. Quem mexer na tela depois so muda o quanto sobra de preto
 * em volta dela.
 */
internal object CinemaRoom
{
	const val WIDTH = 3072
	const val HEIGHT = 1536

	// Dimensoes da sala, em metros, relativas ao olho.
	private const val WALL_X = 4.5f
	private const val FRONT_Z = -4.6f
	private const val BACK_Z = 3.2f
	private const val CEILING_Y = 1.7f
	private const val FLOOR_Y = -1.15f

	// Abertura da tela na parede da frente (meia largura/altura).
	private const val SCREEN_HALF_W = 2.8f
	private const val SCREEN_HALF_H = 1.58f
	private const val SCREEN_Y = 0.0f

	private const val ROW_STEP = 1.1f
	private const val DROP = 0.35f

	/** Material: 0 fosco, 1 tapete (xadrez), 2 veludo. */
	private class Box(
		val x0: Float, val x1: Float, val y0: Float, val y1: Float, val z0: Float, val z1: Float,
		val r: Float, val g: Float, val b: Float, val material: Int)

	private class Light(
		val x: Float, val y: Float, val z: Float,
		val r: Float, val g: Float, val b: Float, val intensity: Float, val reach: Float)

	private val boxes: Array<Box> = buildBoxes().toTypedArray()
	private val lights: Array<Light> = buildLights().toTypedArray()

	// Pontos de luz do teto e arandelas, fora do laco por pixel.
	private val ceilingX = floatArrayOf(-3f, -1.5f, 1.5f, 3f)
	private val ceilingZ = floatArrayOf(-3f, -1f, 1f)

	private fun buildBoxes(): List<Box>
	{
		val list = ArrayList<Box>()
		val carpet = floatArrayOf(0.20f, 0.09f, 0.12f)

		// Degraus: a fileira do espectador e a mais alta; cada uma a frente
		// desce um degrau, como em cinema com arquibancada. A ultima laje vai
		// ate a parede da frente.
		var zNear = BACK_Z
		for(row in 0..3)
		{
			val top = FLOOR_Y - DROP * row
			val zFar = if(row == 3) FRONT_Z else -0.55f - ROW_STEP * row
			list.add(Box(-WALL_X, WALL_X, top - 3f, top, zFar, zNear,
					carpet[0], carpet[1], carpet[2], 1))
			zNear = zFar
		}

		// Encostos das poltronas: veludo vermelho-escuro, voltados para o espectador.
		val velvet = floatArrayOf(0.17f, 0.04f, 0.08f)
		// Fileira 0 (a do espectador): os vizinhos dos dois lados e o encosto da
		// propria poltrona, atras da cabeca.
		for(k in -6..6)
		{
			val x = k * 0.62f
			val top = if(k == 0) 0.10f else FLOOR_Y + 1.0f
			list.add(Box(x - 0.27f, x + 0.27f, FLOOR_Y, top, 0.32f, 0.46f,
					velvet[0], velvet[1], velvet[2], 2))
		}
		for(row in 1..3)
		{
			val floor = FLOOR_Y - DROP * row
			val z = -ROW_STEP * row
			for(k in -5..5)
			{
				val x = k * 0.62f
				list.add(Box(x - 0.27f, x + 0.27f, floor, floor + 1.0f,
						z - 0.14f, z, velvet[0], velvet[1], velvet[2], 2))
			}
		}
		return list
	}

	private fun buildLights(): List<Light>
	{
		val list = ArrayList<Light>()
		// A propria tela: luz fria e a mais forte; ilumina o piso, o teto e as
		// paredes perto dela e deixa as poltronas em silhueta.
		list.add(Light(0f, 0f, -3.4f, 0.75f, 0.85f, 1.0f, 2.2f, 2.8f))
		// Arandelas nas duas paredes laterais, luz quente e baixa.
		for(side in intArrayOf(-1, 1))
		{
			for(z in floatArrayOf(-3.2f, -1.2f, 0.8f, 2.6f))
				list.add(Light(side * (WALL_X - 0.12f), 0.15f, z, 1.0f, 0.68f, 0.38f, 0.55f, 0.9f))
		}
		return list
	}

	// Posicoes dos arandelas, para desenhar a luminaria em si.
	private val sconceZ = floatArrayOf(-3.2f, -1.2f, 0.8f, 2.6f)

	/** Desenha a sala inteira. Leva alguns segundos; chamar fora da thread principal. */
	fun render(): Bitmap
	{
		val pixels = IntArray(WIDTH * HEIGHT)
		val workers = max(2, min(4, Runtime.getRuntime().availableProcessors()))
		val threads = ArrayList<Thread>()
		for(w in 0 until workers)
		{
			val t = Thread {
				val tracer = Tracer()
				var y = w
				while(y < HEIGHT)
				{
					tracer.row(y, pixels)
					y += workers
				}
			}
			t.isDaemon = true
			t.start()
			threads.add(t)
		}
		for(t in threads)
			t.join()

		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
		return bitmap
	}

	/** Um por thread: guarda o resultado da ultima intersecao sem alocar. */
	private class Tracer
	{
		var hitT = 0f
		var nx = 0f
		var ny = 0f
		var nz = 0f
		var material = 0
		var br = 0f
		var bg = 0f
		var bb = 0f
		var emissive = false

		fun row(y: Int, out: IntArray)
		{
			val lat = (0.5f - (y + 0.5f) / HEIGHT) * PI.toFloat()
			val cl = cos(lat)
			val sl = sin(lat)
			for(x in 0 until WIDTH)
			{
				val lon = ((x + 0.5f) / WIDTH - 0.5f) * 2f * PI.toFloat()
				val dx = sin(lon) * cl
				val dy = sl
				val dz = -cos(lon) * cl
				out[y * WIDTH + x] = shade(dx, dy, dz)
			}
		}

		private fun shade(dxIn: Float, dyIn: Float, dzIn: Float): Int
		{
			val dx = if(abs(dxIn) < 1e-6f) 1e-6f else dxIn
			val dy = if(abs(dyIn) < 1e-6f) 1e-6f else dyIn
			val dz = if(abs(dzIn) < 1e-6f) 1e-6f else dzIn

			// Casca da sala: o plano mais proximo entre as paredes e o teto.
			var t = Float.MAX_VALUE
			if(dx > 0f) { val tt = WALL_X / dx; if(tt < t) { t = tt; nx = -1f; ny = 0f; nz = 0f } }
			else { val tt = -WALL_X / dx; if(tt < t) { t = tt; nx = 1f; ny = 0f; nz = 0f } }
			if(dz < 0f) { val tt = FRONT_Z / dz; if(tt < t) { t = tt; nx = 0f; ny = 0f; nz = 1f } }
			else { val tt = BACK_Z / dz; if(tt < t) { t = tt; nx = 0f; ny = 0f; nz = -1f } }
			if(dy > 0f) { val tt = CEILING_Y / dy; if(tt < t) { t = tt; nx = 0f; ny = -1f; nz = 0f } }
			var shell = true

			// Blocos: so os que um raio nesta direcao pode alcancar. Para cima
			// (dy >= 0) nada que esteja abaixo do olho entra.
			for(b in boxes)
			{
				if(dy >= 0f && b.y1 <= 0f)
					continue
				val ix = 1f / dx
				val iy = 1f / dy
				val iz = 1f / dz
				val ax = b.x0 * ix
				val bx = b.x1 * ix
				val ay = b.y0 * iy
				val by = b.y1 * iy
				val az = b.z0 * iz
				val bz = b.z1 * iz
				val tminX = min(ax, bx); val tmaxX = max(ax, bx)
				val tminY = min(ay, by); val tmaxY = max(ay, by)
				val tminZ = min(az, bz); val tmaxZ = max(az, bz)
				val tmin = max(tminX, max(tminY, tminZ))
				val tmax = min(tmaxX, min(tmaxY, tmaxZ))
				if(tmax <= 0f || tmin >= tmax || tmin <= 0f || tmin >= t)
					continue
				t = tmin
				shell = false
				material = b.material
				br = b.r; bg = b.g; bb = b.b
				nx = 0f; ny = 0f; nz = 0f
				if(tmin == tminX) nx = if(dx > 0f) -1f else 1f
				else if(tmin == tminY) ny = if(dy > 0f) -1f else 1f
				else nz = if(dz > 0f) -1f else 1f
			}

			val px = dx * t
			val py = dy * t
			val pz = dz * t
			hitT = t
			emissive = false
			if(shell)
				paintShell(px, py, pz)
			else if(material == 1)
				paintCarpet(px, py, pz)

			if(emissive)
				return pack(br, bg, bb)

			// Luz: ambiente fraco mais cada ponto de luz.
			var lr = 0.07f
			var lg = 0.07f
			var lb = 0.08f
			for(l in lights)
			{
				val lx = l.x - px
				val ly = l.y - py
				val lz = l.z - pz
				val d2 = lx * lx + ly * ly + lz * lz
				val inv = 1f / sqrt(d2)
				val ndl = (nx * lx + ny * ly + nz * lz) * inv
				if(ndl <= 0f)
					continue
				val a = l.intensity * ndl / (1f + d2 / (l.reach * l.reach))
				lr += l.r * a
				lg += l.g * a
				lb += l.b * a
			}
			// O albedo esta em "sRGB", entao albedo * sqrt(luz) e a cor ja
			// codificada para a textura sRGB do swapchain.
			return pack(br * sqrt(lr), bg * sqrt(lg), bb * sqrt(lb))
		}

		private fun paintShell(px: Float, py: Float, pz: Float)
		{
			material = 0
			// Parede da frente: abertura da tela, cortina e rodape.
			if(nz > 0.5f)
			{
				val dx = abs(px)
				val dyScreen = abs(py - SCREEN_Y)
				if(dx < SCREEN_HALF_W && dyScreen < SCREEN_HALF_H)
				{
					// Pano da tela: quase preto, com uma moldura fina mais clara.
					val edge = min(SCREEN_HALF_W - dx, SCREEN_HALF_H - dyScreen)
					val v = if(edge < 0.06f) 0.13f else 0.025f
					br = v; bg = v; bb = v * 1.1f
					return
				}
				if(py < SCREEN_Y - SCREEN_HALF_H - 0.1f)
				{
					br = 0.08f; bg = 0.065f; bb = 0.07f
					return
				}
				// Cortina: dobras verticais.
				val fold = 0.55f + 0.45f * (0.5f + 0.5f * sin(px * 9.0f))
				br = 0.30f * fold; bg = 0.06f * fold; bb = 0.09f * fold
				return
			}
			// Parede de tras: cabine de projecao, uma janelinha acesa.
			if(nz < -0.5f)
			{
				br = 0.10f; bg = 0.09f; bb = 0.11f
				if(abs(px) < 0.35f && py > 0.5f && py < 0.85f)
				{
					br = 0.95f; bg = 0.72f; bb = 0.45f
					emissive = true
				}
				return
			}
			// Teto: paineis e pontos de luz embutidos.
			if(ny < -0.5f)
			{
				br = 0.11f; bg = 0.10f; bb = 0.12f
				val gx = px / 1.0f - floor(px / 1.0f)
				val gz = pz / 1.0f - floor(pz / 1.0f)
				if(gx < 0.02f || gz < 0.02f)
				{
					br *= 0.5f; bg *= 0.5f; bb *= 0.5f
				}
				// Pontos de luz nos cruzamentos de x = +-1,5 / +-3 e z = -3, -1, 1.
				for(sx in ceilingX)
				{
					for(sz in ceilingZ)
					{
						val ex = px - sx
						val ez = pz - sz
						if(ex * ex + ez * ez < 0.0064f)
						{
							br = 1.0f; bg = 0.85f; bb = 0.62f
							emissive = true
							return
						}
					}
				}
				return
			}
			// Paredes laterais: paineis acusticos com friso e arandelas.
			br = 0.13f; bg = 0.11f; bb = 0.13f
			val stripe = 0.7f + 0.3f * (0.5f + 0.5f * sin(pz * 7.0f))
			br *= stripe; bg *= stripe; bb *= stripe
			if(abs(py + 0.35f) < 0.02f)
			{
				br = 0.30f; bg = 0.20f; bb = 0.12f
			}
			for(z in sconceZ)
			{
				val ez = pz - z
				val ey = py - 0.15f
				if(ez * ez * 3f + ey * ey < 0.012f)
				{
					br = 1.0f; bg = 0.78f; bb = 0.5f
					emissive = true
					return
				}
			}
		}

		private fun paintCarpet(px: Float, py: Float, pz: Float)
		{
			if(ny < 0.5f)
				return
			// Piso: xadrez suave de tapete.
			val cx = floor(px / 0.6f).toInt()
			val cz = floor(pz / 0.6f).toInt()
			val shade = if(((cx + cz) and 1) == 0) 1.0f else 0.8f
			br *= shade; bg *= shade; bb *= shade

			// Faixa de LED na beira de cada degrau, do lado do espectador: o
			// que da forma aos degraus no escuro. (O espelho do degrau fica
			// escondido atras da beira; o olho enxerga so o topo.)
			for(k in 0..2)
			{
				val lip = -0.55f - ROW_STEP * k
				if(abs(py - (FLOOR_Y - DROP * k)) < 0.01f && pz > lip && pz < lip + 0.07f)
				{
					br = 1.0f; bg = 0.74f; bb = 0.46f
					emissive = true
					return
				}
			}
		}

		private fun pack(r: Float, g: Float, b: Float): Int
		{
			val ri = (min(1f, max(0f, r)) * 255f + 0.5f).toInt()
			val gi = (min(1f, max(0f, g)) * 255f + 0.5f).toInt()
			val bi = (min(1f, max(0f, b)) * 255f + 0.5f).toInt()
			return (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
		}
	}
}
