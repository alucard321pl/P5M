// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * O que a tela de teste do pad sabe sobre o controle: o que esta apertado
 * agora, o que ja funcionou alguma vez e ate onde cada analogico foi.
 *
 * Sem nada de Android de proposito: so recebe numeros. Assim a logica que
 * decide "este botao ja foi testado" ou "este analogico chegou ao limite"
 * pode ser conferida num computador, sem headset, e a tela fica so com o
 * desenho.
 *
 * Os codigos de tecla sao os do Android (`KeyEvent.KEYCODE_*`), escritos como
 * numero para esta classe nao puxar a plataforma. O mapa e o do Quest com um
 * DualSense: A = Cruz, B = Bola, X = Quadrado, Y = Triangulo.
 */
internal class PadState
{
	private val apertados = HashSet<Int>()
	private val testados = HashSet<Int>()

	// D-pad do DualSense chega como eixo (hat), nao como tecla. Os dois caminhos
	// valem: algumas combinacoes de controle e driver mandam o outro.
	private var hatX = 0f
	private var hatY = 0f

	var lx = 0f; private set
	var ly = 0f; private set
	var rx = 0f; private set
	var ry = 0f; private set
	var l2 = 0f; private set
	var r2 = 0f; private set

	/** Maior distancia do centro que cada analogico alcancou, em fracao do curso. */
	var maxEsq = 0f; private set
	var maxDir = 0f; private set
	var maxL2 = 0f; private set
	var maxR2 = 0f; private set

	/** Registra uma tecla. Devolve false se o codigo nao e de nenhum botao conhecido. */
	fun tecla(codigo: Int, apertou: Boolean): Boolean
	{
		if(codigo !in TODOS)
			return false
		if(apertou)
		{
			apertados.add(codigo)
			testados.add(codigo)
		}
		else
			apertados.remove(codigo)
		return true
	}

	/** O toque no painel do DualSense e um "botao" a mais, sem tecla propria. */
	fun painel(apertado: Boolean) { tecla(PAINEL, apertado) }

	fun hat(x: Float, y: Float)
	{
		hatX = x
		hatY = y
		if(x < -LIMIAR_HAT) testados.add(CIMA + 2)
		if(x > LIMIAR_HAT) testados.add(CIMA + 3)
		if(y < -LIMIAR_HAT) testados.add(CIMA)
		if(y > LIMIAR_HAT) testados.add(CIMA + 1)
	}

	fun eixos(lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float)
	{
		this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry
		this.l2 = l2; this.r2 = r2
		maxEsq = max(maxEsq, sqrt(lx * lx + ly * ly))
		maxDir = max(maxDir, sqrt(rx * rx + ry * ry))
		maxL2 = max(maxL2, l2)
		maxR2 = max(maxR2, r2)
	}

	fun apertado(codigo: Int): Boolean
	{
		if(codigo in apertados)
			return true
		return when(codigo)
		{
			CIMA -> hatY < -LIMIAR_HAT
			CIMA + 1 -> hatY > LIMIAR_HAT
			CIMA + 2 -> hatX < -LIMIAR_HAT
			CIMA + 3 -> hatX > LIMIAR_HAT
			else -> false
		}
	}

	fun testado(codigo: Int) = codigo in testados

	fun botoesTestados(): Int = TODOS.count { it in testados }

	fun analogicosTestados(): Int =
		(if(maxEsq >= CURSO_CHEIO) 1 else 0) + (if(maxDir >= CURSO_CHEIO) 1 else 0)

	fun gatilhosTestados(): Int =
		(if(maxL2 >= GATILHO_CHEIO) 1 else 0) + (if(maxR2 >= GATILHO_CHEIO) 1 else 0)

	fun zerar()
	{
		apertados.clear()
		testados.clear()
		maxEsq = 0f; maxDir = 0f; maxL2 = 0f; maxR2 = 0f
	}

	companion object
	{
		const val CRUZ = 96
		const val BOLA = 97
		const val QUADRADO = 99
		const val TRIANGULO = 100
		const val L1 = 102
		const val R1 = 103
		const val L2 = 104
		const val R2 = 105
		const val L3 = 106
		const val R3 = 107
		const val OPCOES = 108
		const val CRIAR = 109
		const val PS = 110

		// D-pad como teclas: 19..22 (cima, baixo, esquerda, direita). Os
		// codigos "de baixo" do hat seguem a mesma ordem, em CIMA + 0..3.
		const val CIMA = 19
		const val BAIXO = 20
		const val ESQUERDA = 21
		const val DIREITA = 22

		/** Pseudo-codigo do painel de toque; nenhuma tecla do Android usa este numero. */
		const val PAINEL = 1000

		val TODOS = intArrayOf(CRUZ, BOLA, QUADRADO, TRIANGULO, L1, R1, L2, R2, L3, R3,
			OPCOES, CRIAR, PS, CIMA, BAIXO, ESQUERDA, DIREITA, PAINEL)

		const val LIMIAR_HAT = 0.5f

		/** Um analogico "chegou ao limite" quando passa de 90% do curso. */
		const val CURSO_CHEIO = 0.9f
		const val GATILHO_CHEIO = 0.95f

		/** Nome em ingles, que e a chave da traducao. */
		fun nome(codigo: Int): String = when(codigo)
		{
			CRUZ -> "Cross"
			BOLA -> "Circle"
			QUADRADO -> "Square"
			TRIANGULO -> "Triangle"
			L1 -> "L1"
			R1 -> "R1"
			L2 -> "L2"
			R2 -> "R2"
			L3 -> "L3"
			R3 -> "R3"
			OPCOES -> "Options"
			CRIAR -> "Create"
			PS -> "PS"
			CIMA -> "D-pad up"
			BAIXO -> "D-pad down"
			ESQUERDA -> "D-pad left"
			DIREITA -> "D-pad right"
			PAINEL -> "Touchpad"
			else -> "key $codigo"
		}

		/** Valor de um eixo a duas casas e sinal, ponto como separador. */
		fun fmt(v: Float): String = "%+.3f".format(java.util.Locale.US, v)

		fun pct(v: Float): String = "%.0f%%".format(java.util.Locale.US, abs(v) * 100f)
	}
}
