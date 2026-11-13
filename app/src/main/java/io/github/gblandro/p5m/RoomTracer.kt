// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor das salas virtuais: lanca um raio por pixel de uma imagem
 * equiretangular e pinta o que ele acerta. Nao conhece Android — o
 * [CinemaRoom] embrulha o resultado num Bitmap —, o que permite desenhar e
 * conferir as salas num computador comum.
 *
 * Cada sala e uma caixa com blocos dentro (moveis, degraus, entulho) e alguns
 * pontos de luz; materiais e texturas sao funcoes de posicao. Sem sombras:
 * a luz de cada ponto so depende da orientacao da superficie e da distancia.
 *
 * Eixos como no OpenXR: x para a direita, y para cima, a frente da sala em
 * -z. O olho esta na origem, 1,15 m acima do piso da fileira (altura de quem
 * esta sentado). O centro da imagem aponta para -z.
 */
internal object RoomTracer
{
	const val WIDTH = 3072
	const val HEIGHT = 1536

	const val STYLE_CINEMA = 0
	const val STYLE_HORROR = 1

	private const val FLOOR_Y = -1.15f

	// Cinema: caixa larga, tela na parede da frente entre cortinas.
	private const val C_WALL_X = 4.5f
	private const val C_FRONT_Z = -4.6f
	private const val C_BACK_Z = 3.2f
	private const val C_CEILING_Y = 1.7f
	// Abertura da tela na parede da frente (meia largura/altura). Tem o
	// tamanho que o preset de cinema ocupa visto da parede, com uma folga.
	private const val SCREEN_HALF_W = 2.8f
	private const val SCREEN_HALF_H = 1.58f
	private const val ROW_STEP = 1.1f
	private const val DROP = 0.35f

	// Horror: cela estreita; a tela do preset quase enche a largura.
	private const val H_WALL_X = 3.0f
	private const val H_FRONT_Z = -4.6f
	private const val H_BACK_Z = 2.6f
	private const val H_CEILING_Y = 1.9f

	// Materiais dos blocos.
	private const val MAT_MATTE = 0
	private const val MAT_CARPET = 1
	private const val MAT_VELVET = 2
	private const val MAT_WOOD = 3
	private const val MAT_IRON = 4
	private const val MAT_DEBRIS = 5
	private const val MAT_CONCRETE = 6

	private class Box(
		val x0: Float, val x1: Float, val y0: Float, val y1: Float, val z0: Float, val z1: Float,
		val r: Float, val g: Float, val b: Float, val material: Int)

	private class Light(
		val x: Float, val y: Float, val z: Float,
		val r: Float, val g: Float, val b: Float, val intensity: Float, val reach: Float)

	private class Scene(
		val boxes: Array<Box>, val lights: Array<Light>,
		val wallX: Float, val frontZ: Float, val backZ: Float, val ceilY: Float,
		val ambient: Float)

	private val cinema: Scene by lazy { buildCinema() }
	private val horror: Scene by lazy { buildHorror() }

	private fun sceneFor(style: Int): Scene = if(style == STYLE_HORROR) horror else cinema

	// ------------------------------------------------------------ cinema

	private val sconceZ = floatArrayOf(-3.2f, -1.2f, 0.8f, 2.6f)
	private val ceilingX = floatArrayOf(-3f, -1.5f, 1.5f, 3f)
	private val ceilingZ = floatArrayOf(-3f, -1f, 1f)

	private fun buildCinema(): Scene
	{
		val list = ArrayList<Box>()
		val carpet = floatArrayOf(0.20f, 0.09f, 0.12f)

		// Degraus: a fileira do espectador e a mais alta; cada uma a frente
		// desce um degrau, como em cinema com arquibancada. A ultima laje vai
		// ate a parede da frente.
		var zNear = C_BACK_Z
		for(row in 0..3)
		{
			val top = FLOOR_Y - DROP * row
			val zFar = if(row == 3) C_FRONT_Z else -0.55f - ROW_STEP * row
			list.add(Box(-C_WALL_X, C_WALL_X, top - 3f, top, zFar, zNear,
					carpet[0], carpet[1], carpet[2], MAT_CARPET))
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
					velvet[0], velvet[1], velvet[2], MAT_VELVET))
		}
		for(row in 1..3)
		{
			val floor = FLOOR_Y - DROP * row
			val z = -ROW_STEP * row
			for(k in -5..5)
			{
				val x = k * 0.62f
				list.add(Box(x - 0.27f, x + 0.27f, floor, floor + 1.0f, z - 0.14f, z,
						velvet[0], velvet[1], velvet[2], MAT_VELVET))
			}
		}

		val lights = ArrayList<Light>()
		// A propria tela: luz fria e a mais forte; ilumina o piso, o teto e as
		// paredes perto dela e deixa as poltronas em silhueta.
		lights.add(Light(0f, 0f, -3.4f, 0.75f, 0.85f, 1.0f, 2.2f, 2.8f))
		// Arandelas nas duas paredes laterais, luz quente e baixa.
		for(side in intArrayOf(-1, 1))
			for(z in sconceZ)
				lights.add(Light(side * (C_WALL_X - 0.12f), 0.15f, z, 1.0f, 0.68f, 0.38f, 0.55f, 0.9f))

		return Scene(list.toTypedArray(), lights.toTypedArray(),
				C_WALL_X, C_FRONT_Z, C_BACK_Z, C_CEILING_Y, 0.07f)
	}

	// ------------------------------------------------------------ horror

	private fun buildHorror(): Scene
	{
		val list = ArrayList<Box>()
		val none = 0f

		// Piso de concreto: uma laje so.
		list.add(Box(-H_WALL_X, H_WALL_X, FLOOR_Y - 3f, FLOOR_Y, H_FRONT_Z, H_BACK_Z,
				0.17f, 0.15f, 0.13f, MAT_CONCRETE))

		val wood = floatArrayOf(0.30f, 0.19f, 0.11f)
		// Mesa no canto da frente, a esquerda: tampo e quatro pes.
		val tx0 = -2.95f; val tx1 = -2.1f; val tz0 = -4.3f; val tz1 = -3.5f
		val topY = FLOOR_Y + 0.75f
		list.add(Box(tx0, tx1, topY - 0.05f, topY, tz0, tz1, wood[0], wood[1], wood[2], MAT_WOOD))
		for(lx in floatArrayOf(tx0, tx1 - 0.06f))
			for(lz in floatArrayOf(tz0, tz1 - 0.06f))
				list.add(Box(lx, lx + 0.06f, FLOOR_Y, topY - 0.05f, lz, lz + 0.06f,
						wood[0] * 0.9f, wood[1] * 0.9f, wood[2] * 0.9f, MAT_WOOD))

		// Cadeira caida de lado, a direita: assento, encosto e pes.
		val cx0 = 2.25f; val cx1 = 2.7f; val cz0 = -3.2f; val cz1 = -2.75f
		val seatY = FLOOR_Y + 0.45f
		list.add(Box(cx0, cx1, seatY - 0.04f, seatY, cz0, cz1, wood[0], wood[1], wood[2], MAT_WOOD))
		list.add(Box(cx0, cx1, seatY, seatY + 0.45f, cz1 - 0.04f, cz1, wood[0], wood[1], wood[2], MAT_WOOD))
		for(lx in floatArrayOf(cx0, cx1 - 0.04f))
			for(lz in floatArrayOf(cz0, cz1 - 0.04f))
				list.add(Box(lx, lx + 0.04f, FLOOR_Y, seatY - 0.04f, lz, lz + 0.04f,
						wood[0] * 0.9f, wood[1] * 0.9f, wood[2] * 0.9f, MAT_WOOD))

		// Caixote de madeira na parede da direita e pneu na da esquerda.
		list.add(Box(2.1f, 2.95f, FLOOR_Y, FLOOR_Y + 0.5f, -1.5f, -0.7f, 0.24f, 0.16f, 0.10f, MAT_WOOD))
		list.add(Box(-2.95f, -2.2f, FLOOR_Y, FLOOR_Y + 0.3f, -1.1f, -0.35f, 0.06f, 0.06f, 0.06f, MAT_MATTE))

		// Correntes penduradas do teto, perto da parede da frente, e ganchos.
		val chainX = floatArrayOf(-2.75f, -2.5f, 2.55f)
		val chainEnd = floatArrayOf(0.25f, -0.15f, 0.45f)
		for(i in chainX.indices)
		{
			val x = chainX[i]
			val z = H_FRONT_Z + 0.2f
			list.add(Box(x - 0.02f, x + 0.02f, chainEnd[i], H_CEILING_Y, z - 0.02f, z + 0.02f,
					0.14f, 0.12f, 0.10f, MAT_IRON))
			list.add(Box(x - 0.05f, x + 0.05f, chainEnd[i] - 0.10f, chainEnd[i], z - 0.02f, z + 0.02f,
					0.14f, 0.12f, 0.10f, MAT_IRON))
		}

		// Entulho no chao: tabuas, blocos e caixotes pequenos, posicao fixa
		// (gerador pseudoaleatorio com semente) para a sala sair sempre igual.
		var seed = 12345
		fun rnd(): Float
		{
			seed = seed * 1103515245 + 12345
			return ((seed ushr 8) and 0xFFFF) / 65535f
		}
		for(n in 0 until 24)
		{
			val x = -2.8f + rnd() * 5.6f
			val z = -4.2f + rnd() * 6.4f
			// Fora do espaco do espectador (ao redor da origem) e da mesa.
			if(abs(x) < 0.5f && abs(z) < 0.6f)
				continue
			val kind = (rnd() * 3f).toInt()
			val w: Float
			val d: Float
			val h: Float
			val debris: FloatArray
			if(kind == 0)
			{
				// Tabua caida: comprida e fina, no eixo x ou z.
				val len = 0.5f + rnd() * 0.7f
				if(rnd() < 0.5f) { w = len; d = 0.09f } else { w = 0.09f; d = len }
				h = 0.03f
				debris = floatArrayOf(0.27f, 0.19f, 0.12f)
			}
			else
			{
				w = 0.12f + rnd() * 0.22f
				d = 0.12f + rnd() * 0.22f
				h = 0.08f + rnd() * 0.16f
				debris = if(kind == 1) floatArrayOf(0.32f, 0.30f, 0.27f) else floatArrayOf(0.25f, 0.17f, 0.11f)
			}
			if(x > tx0 - w && x < tx1 && z > tz0 - d && z < tz1)
				continue
			list.add(Box(x, x + w, FLOOR_Y, FLOOR_Y + h, z, z + d, debris[0], debris[1], debris[2], MAT_DEBRIS))
		}
		none.toString()

		val lights = ArrayList<Light>()
		// Lampada ambar no teto, no canto da frente a esquerda: de onde vem a
		// luz laranja da cena. Acende a parede de azulejos, a mesa e a poca.
		lights.add(Light(-1.9f, 1.6f, -3.4f, 1.0f, 0.55f, 0.22f, 2.8f, 3.2f))
		// Luminaria na parede da frente, a direita.
		lights.add(Light(2.7f, 0.45f, -4.4f, 1.0f, 0.62f, 0.28f, 1.3f, 2.0f))
		// Reflexo fraco do fundo, so para o espectador nao ficar no breu.
		lights.add(Light(0f, 0.6f, 1.5f, 0.6f, 0.38f, 0.22f, 0.3f, 3.0f))

		return Scene(list.toTypedArray(), lights.toTypedArray(),
				H_WALL_X, H_FRONT_Z, H_BACK_Z, H_CEILING_Y, 0.05f)
	}

	// ------------------------------------------------------------- render

	/** Desenha a sala inteira: ARGB, WIDTH x HEIGHT. Leva alguns segundos. */
	fun render(style: Int): IntArray
	{
		val scene = sceneFor(style)
		val pixels = IntArray(WIDTH * HEIGHT)
		val workers = max(2, min(4, Runtime.getRuntime().availableProcessors()))
		val threads = ArrayList<Thread>()
		for(w in 0 until workers)
		{
			val t = Thread {
				val tracer = Tracer(scene, style)
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
		return pixels
	}

	/** Para conferir a sala num computador: a cor vista numa direcao. */
	internal fun sampler(style: Int): (Float, Float, Float) -> Int
	{
		val tracer = Tracer(sceneFor(style), style)
		return { dx, dy, dz -> tracer.shade(dx, dy, dz) }
	}

	/** Um por thread: guarda o resultado da ultima intersecao sem alocar. */
	private class Tracer(private val scene: Scene, private val style: Int)
	{
		private var nx = 0f
		private var ny = 0f
		private var nz = 0f
		private var material = 0
		private var br = 0f
		private var bg = 0f
		private var bb = 0f
		private var emissive = false
		private var glossy = false

		fun row(y: Int, out: IntArray)
		{
			val lat = (0.5f - (y + 0.5f) / HEIGHT) * PI.toFloat()
			val cl = cos(lat)
			val sl = sin(lat)
			for(x in 0 until WIDTH)
			{
				val lon = ((x + 0.5f) / WIDTH - 0.5f) * 2f * PI.toFloat()
				out[y * WIDTH + x] = shade(sin(lon) * cl, sl, -cos(lon) * cl)
			}
		}

		fun shade(dxIn: Float, dyIn: Float, dzIn: Float): Int
		{
			val dx = if(abs(dxIn) < 1e-6f) 1e-6f else dxIn
			val dy = if(abs(dyIn) < 1e-6f) 1e-6f else dyIn
			val dz = if(abs(dzIn) < 1e-6f) 1e-6f else dzIn

			// Casca da sala: o plano mais proximo entre as paredes e o teto.
			var t = Float.MAX_VALUE
			if(dx > 0f) { val tt = scene.wallX / dx; if(tt < t) { t = tt; nx = -1f; ny = 0f; nz = 0f } }
			else { val tt = -scene.wallX / dx; if(tt < t) { t = tt; nx = 1f; ny = 0f; nz = 0f } }
			if(dz < 0f) { val tt = scene.frontZ / dz; if(tt < t) { t = tt; nx = 0f; ny = 0f; nz = 1f } }
			else { val tt = scene.backZ / dz; if(tt < t) { t = tt; nx = 0f; ny = 0f; nz = -1f } }
			if(dy > 0f) { val tt = scene.ceilY / dy; if(tt < t) { t = tt; nx = 0f; ny = -1f; nz = 0f } }
			var shell = true

			// Blocos: so os que um raio nesta direcao pode alcancar. Para cima
			// (dy >= 0) nada que esteja abaixo do olho entra.
			val ix = 1f / dx
			val iy = 1f / dy
			val iz = 1f / dz
			for(b in scene.boxes)
			{
				if(dy >= 0f && b.y1 <= 0f)
					continue
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
			emissive = false
			glossy = false
			if(shell)
			{
				if(style == STYLE_HORROR) paintHorrorShell(px, py, pz)
				else paintCinemaShell(px, py, pz)
			}
			else
			{
				when(material)
				{
					MAT_CARPET -> paintCarpet(px, py, pz)
					MAT_WOOD -> paintWood(px, py, pz)
					MAT_IRON -> paintIron(py)
					MAT_DEBRIS -> paintDebris(px, pz)
					MAT_CONCRETE -> paintConcrete(px, pz)
				}
			}

			if(emissive)
				return pack(br, bg, bb)

			// Luz: ambiente fraco mais cada ponto de luz.
			var lr = scene.ambient
			var lg = scene.ambient
			var lb = scene.ambient * 1.1f
			var spec = 0f
			var specR = 0f
			var specG = 0f
			var specB = 0f
			for(l in scene.lights)
			{
				val lx = l.x - px
				val ly = l.y - py
				val lz = l.z - pz
				val d2 = lx * lx + ly * ly + lz * lz
				val inv = 1f / sqrt(d2)
				val ndl = (nx * lx + ny * ly + nz * lz) * inv
				if(ndl <= 0f)
					continue
				val fall = l.intensity / (1f + d2 / (l.reach * l.reach))
				val a = fall * ndl
				lr += l.r * a
				lg += l.g * a
				lb += l.b * a
				if(glossy)
				{
					// Brilho especular da poca: meio-vetor entre a luz e o olho.
					val hx = lx * inv - dx
					val hy = ly * inv - dy
					val hz = lz * inv - dz
					val hl = sqrt(hx * hx + hy * hy + hz * hz)
					val nh = max(0f, (nx * hx + ny * hy + nz * hz) / hl)
					var p = nh * nh; p *= p; p *= p; p *= p
					val s = p * p * p * fall
					specR += l.r * s
					specG += l.g * s
					specB += l.b * s
					spec += s
				}
			}
			// O albedo esta em "sRGB", entao albedo * sqrt(luz) e a cor ja
			// codificada para a textura sRGB do swapchain.
			var r = br * sqrt(lr)
			var g = bg * sqrt(lg)
			var b = bb * sqrt(lb)
			if(glossy && spec > 0f)
			{
				r += sqrt(specR) * 0.9f
				g += sqrt(specG) * 0.9f
				b += sqrt(specB) * 0.9f
			}
			return pack(r, g, b)
		}

		// -------------------------------------------------------- ruido

		private fun hash(ix: Int, iy: Int): Float
		{
			var h = ix * 374761393 + iy * 668265263
			h = (h xor (h ushr 13)) * 1274126177
			h = h xor (h ushr 16)
			return (h and 0xFFFF) / 65535f
		}

		private fun vnoise(x: Float, y: Float): Float
		{
			val x0 = floor(x)
			val y0 = floor(y)
			val fx = x - x0
			val fy = y - y0
			val ix = x0.toInt()
			val iy = y0.toInt()
			val sx = fx * fx * (3f - 2f * fx)
			val sy = fy * fy * (3f - 2f * fy)
			val a = hash(ix, iy)
			val b = hash(ix + 1, iy)
			val c = hash(ix, iy + 1)
			val d = hash(ix + 1, iy + 1)
			return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
		}

		private fun frac(v: Float): Float = v - floor(v)

		private fun set(r: Float, g: Float, b: Float)
		{
			br = r; bg = g; bb = b
		}

		private fun mix(r: Float, g: Float, b: Float, t: Float)
		{
			br += (r - br) * t
			bg += (g - bg) * t
			bb += (b - bb) * t
		}

		// ------------------------------------------------ cinema: casca

		private fun paintCinemaShell(px: Float, py: Float, pz: Float)
		{
			// Parede da frente: abertura da tela, cortina e rodape.
			if(nz > 0.5f)
			{
				val dx = abs(px)
				val dyScreen = abs(py)
				if(dx < SCREEN_HALF_W && dyScreen < SCREEN_HALF_H)
				{
					// Pano da tela: quase preto, com uma moldura fina mais clara.
					val edge = min(SCREEN_HALF_W - dx, SCREEN_HALF_H - dyScreen)
					val v = if(edge < 0.06f) 0.13f else 0.025f
					set(v, v, v * 1.1f)
					return
				}
				if(py < -SCREEN_HALF_H - 0.1f)
				{
					set(0.08f, 0.065f, 0.07f)
					return
				}
				// Cortina: dobras verticais.
				val fold = 0.55f + 0.45f * (0.5f + 0.5f * sin(px * 9.0f))
				set(0.30f * fold, 0.06f * fold, 0.09f * fold)
				return
			}
			// Parede de tras: cabine de projecao, uma janelinha acesa.
			if(nz < -0.5f)
			{
				set(0.10f, 0.09f, 0.11f)
				if(abs(px) < 0.35f && py > 0.5f && py < 0.85f)
				{
					set(0.95f, 0.72f, 0.45f)
					emissive = true
				}
				return
			}
			// Teto: paineis e pontos de luz embutidos.
			if(ny < -0.5f)
			{
				set(0.11f, 0.10f, 0.12f)
				if(frac(px) < 0.02f || frac(pz) < 0.02f)
					set(br * 0.5f, bg * 0.5f, bb * 0.5f)
				// Pontos de luz nos cruzamentos de x = +-1,5 / +-3 e z = -3, -1, 1.
				for(sx in ceilingX)
				{
					for(sz in ceilingZ)
					{
						val ex = px - sx
						val ez = pz - sz
						if(ex * ex + ez * ez < 0.0064f)
						{
							set(1.0f, 0.85f, 0.62f)
							emissive = true
							return
						}
					}
				}
				return
			}
			// Paredes laterais: paineis acusticos com friso e arandelas.
			set(0.13f, 0.11f, 0.13f)
			val stripe = 0.7f + 0.3f * (0.5f + 0.5f * sin(pz * 7.0f))
			set(br * stripe, bg * stripe, bb * stripe)
			if(abs(py + 0.35f) < 0.02f)
				set(0.30f, 0.20f, 0.12f)
			for(z in sconceZ)
			{
				val ez = pz - z
				val ey = py - 0.15f
				if(ez * ez * 3f + ey * ey < 0.012f)
				{
					set(1.0f, 0.78f, 0.5f)
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
			set(br * shade, bg * shade, bb * shade)

			// Faixa de LED na beira de cada degrau, do lado do espectador: o
			// que da forma aos degraus no escuro. (O espelho do degrau fica
			// escondido atras da beira; o olho enxerga so o topo.)
			for(k in 0..2)
			{
				val lip = -0.55f - ROW_STEP * k
				if(abs(py - (FLOOR_Y - DROP * k)) < 0.01f && pz > lip && pz < lip + 0.07f)
				{
					set(1.0f, 0.74f, 0.46f)
					emissive = true
					return
				}
			}
		}

		// ------------------------------------------------ horror: casca

		private fun paintHorrorShell(px: Float, py: Float, pz: Float)
		{
			val wallX = scene.wallX
			// Parede da frente: azulejos manchados de ferrugem.
			if(nz > 0.5f)
			{
				// Luminaria acesa na parede.
				if(abs(px - 2.75f) < 0.2f && abs(py - 0.45f) < 0.08f)
				{
					set(1.0f, 0.82f, 0.5f)
					emissive = true
					return
				}
				val u = px / 0.3f
				val v = py / 0.3f
				val fu = frac(u)
				val fv = frac(v)
				if(fu < 0.04f || fv < 0.04f)
					set(0.24f, 0.21f, 0.17f)
				else
				{
					val k = 0.88f + 0.12f * hash(floor(u).toInt(), floor(v).toInt())
					set(0.66f * k, 0.58f * k, 0.44f * k)
				}
				// Manchas: escorrem do teto em faixas, e uma mancha larga por cima.
				val drop = scene.ceilY - py
				val streakLen = 1.2f + 2.0f * vnoise(px * 5.0f, 0.5f)
				var stain = 0f
				if(drop < streakLen)
					stain = (1f - drop / streakLen) * (0.45f + 0.55f * vnoise(px * 13.0f, py * 3.0f))
				val blotch = max(0f, vnoise(px * 1.3f + 10f, py * 1.3f) - 0.5f) * 2f
				stain = min(1f, stain * 1.4f + blotch * 0.8f)
				mix(0.56f, 0.17f, 0.06f, stain)
				// Sujeira que sobe do chao.
				val grime = min(1f, max(0f, (-0.3f - py) * 0.6f))
				set(br * (1f - 0.6f * grime), bg * (1f - 0.6f * grime), bb * (1f - 0.6f * grime))
				return
			}
			// Parede de tras: concreto sujo.
			if(nz < -0.5f)
			{
				val k = 0.6f + 0.4f * vnoise(px * 2f, py * 2f)
				set(0.30f * k, 0.28f * k, 0.26f * k)
				return
			}
			// Teto: reboco descascando, com um duto atravessando.
			if(ny < -0.5f)
			{
				// Lampada embutida, acesa, de onde vem a luz ambar.
				if(px > -2.2f && px < -1.6f && pz > -3.7f && pz < -3.1f)
				{
					set(1.0f, 0.74f, 0.42f)
					emissive = true
					return
				}
				set(0.50f, 0.42f, 0.30f)
				val dirt = 0.55f + 0.45f * vnoise(px * 4f, pz * 4f)
				set(br * dirt, bg * dirt, bb * dirt)
				if(vnoise(px * 1.2f + 5f, pz * 1.2f) > 0.58f)
					set(0.14f, 0.12f, 0.11f)
				if(abs(pz + 2.2f) < 0.12f)
					set(0.15f, 0.13f, 0.12f)
				return
			}
			// Paredes laterais: a da esquerda (normal +x) e de blocos, a da
			// direita (normal -x) e de tijolo.
			if(nx > 0f)
			{
				val row = floor(py / 0.2f).toInt()
				val uu = pz / 0.4f + (row and 1) * 0.5f
				if(frac(uu) < 0.035f || frac(py / 0.2f) < 0.06f)
					set(0.20f, 0.19f, 0.18f)
				else
				{
					val k = 0.85f + 0.15f * hash(floor(uu).toInt(), row)
					set(0.34f * k, 0.34f * k, 0.32f * k)
				}
				val dirt = 0.55f + 0.45f * vnoise(pz * 3f, py * 3f)
				set(br * dirt, bg * dirt, bb * dirt)
				// Porta dupla de madeira.
				if(pz > -3.0f && pz < -1.5f && py > FLOOR_Y && py < 0.85f)
				{
					val plank = floor((pz + 3.0f) / 0.19f).toInt()
					val edge = frac((pz + 3.0f) / 0.19f)
					val k = 0.7f + 0.3f * hash(plank, 7)
					set(0.40f * k, 0.28f * k, 0.17f * k)
					if(edge < 0.06f || abs(pz + 2.25f) < 0.02f)
						set(0.12f, 0.09f, 0.06f)
					if(py > 0.8f || pz < -2.95f || pz > -1.55f)
						set(0.20f, 0.15f, 0.10f)
				}
			}
			else
			{
				val row = floor(py / 0.075f).toInt()
				val uu = pz / 0.22f + (row and 1) * 0.5f
				if(frac(uu) < 0.04f || frac(py / 0.075f) < 0.1f)
					set(0.20f, 0.18f, 0.16f)
				else
				{
					val k = 0.75f + 0.25f * hash(floor(uu).toInt(), row)
					set(0.38f * k, 0.15f * k, 0.10f * k)
				}
				val dirt = 0.5f + 0.5f * vnoise(pz * 3f, py * 3f)
				set(br * dirt, bg * dirt, bb * dirt)
				// Porta de ferro enferrujada, com um emblema redondo.
				if(pz > -4.0f && pz < -2.6f && py > FLOOR_Y && py < 0.95f)
				{
					val k = 0.75f + 0.25f * vnoise(pz * 6f, py * 6f)
					set(0.36f * k, 0.23f * k, 0.15f * k)
					val ez = pz + 3.3f
					val ey = py - 0.2f
					val ring = sqrt(ez * ez + ey * ey)
					if(abs(ring - 0.2f) < 0.018f)
						set(0.55f, 0.45f, 0.30f)
					if(pz < -3.93f || pz > -2.67f || py > 0.88f)
						set(0.18f, 0.12f, 0.08f)
				}
				// Quadro de fusiveis: placa escura com botoes laranja e brancos.
				if(pz > -2.4f && pz < -1.2f && py > -0.3f && py < 0.9f)
				{
					set(0.22f, 0.20f, 0.18f)
					val cz = frac((pz + 2.4f) / 0.2f) - 0.5f
					val cy = frac((py + 0.3f) / 0.2f) - 0.5f
					if(cz * cz + cy * cy < 0.07f)
					{
						val which = hash(floor((pz + 2.4f) / 0.2f).toInt(), floor((py + 0.3f) / 0.2f).toInt())
						if(which < 0.6f) set(0.78f, 0.36f, 0.14f) else set(0.80f, 0.78f, 0.72f)
					}
				}
			}
			// Cano que corre pelas duas paredes, com um fio de brilho.
			if(py > 1.08f && py < 1.2f)
			{
				set(0.16f, 0.14f, 0.13f)
				if(py > 1.165f)
					set(0.30f, 0.27f, 0.24f)
			}
			wallX.toString()
		}

		// ------------------------------------------------ horror: blocos

		private fun paintWood(px: Float, py: Float, pz: Float)
		{
			val grain = 0.75f + 0.25f * vnoise(px * 22f + pz * 3f, py * 3f)
			set(br * grain, bg * grain, bb * grain)
		}

		private fun paintIron(py: Float)
		{
			// Elos: faixas alternadas ao longo da corrente.
			val link = 0.45f + 0.55f * (0.5f + 0.5f * sin(py * 75f))
			set(br * link, bg * link, bb * link)
		}

		private fun paintDebris(px: Float, pz: Float)
		{
			val k = 0.65f + 0.35f * vnoise(px * 30f, pz * 30f)
			set(br * k, bg * k, bb * k)
		}

		private fun paintConcrete(px: Float, pz: Float)
		{
			if(ny < 0.5f)
				return
			val k = 0.55f + 0.45f * vnoise(px * 2.5f, pz * 2.5f)
			set(0.22f * k, 0.19f * k, 0.16f * k)
			// Manchas escuras de oleo e sujeira.
			val stain = max(0f, vnoise(px * 1.1f + 3f, pz * 1.1f) - 0.55f) * 2.2f
			set(br * (1f - 0.5f * stain), bg * (1f - 0.5f * stain), bb * (1f - 0.5f * stain))
			// Poca d'agua no meio da cela: escura e brilhante.
			val u = (px - 0.2f) / 1.5f
			val w = (pz + 1.7f) / 1.0f
			val d = u * u + w * w + 0.25f * (vnoise(px * 3f, pz * 3f) - 0.5f)
			if(d < 1f)
			{
				set(br * 0.55f, bg * 0.5f, bb * 0.45f)
				glossy = true
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
