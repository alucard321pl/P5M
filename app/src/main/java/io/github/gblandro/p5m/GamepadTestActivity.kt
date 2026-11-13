// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Teste do pad: mostra ao vivo o que o controle manda e deixa vibrar os motores.
 *
 * ## Para que serve
 *
 * Quando algo "nao funciona em jogo", ha duas causas muito diferentes: o
 * controle (ou o driver Bluetooth) nao entrega o evento ao Android, ou o app
 * entrega e algo mais adiante perde. Daqui sai a primeira metade da resposta
 * sem console, sem stream e sem sessao: se o botao acende aqui, o Android o
 * recebeu.
 *
 * ## O que mostra
 *
 * Cada botao acende enquanto apertado e fica verde depois de ter funcionado
 * uma vez, de modo que um controle inteiro se confere de uma passada, sem
 * ficar procurando qual botao faltou. Os analogicos e os gatilhos mostram o
 * valor cru e ate onde ja foram. O anel de cada analogico e a zona morta que
 * o app corta, para ver o drift contra o que chega ao jogo.
 *
 * ## Decisoes que valem registrar
 *
 * **Consome as teclas do pad.** Sem isso, apertar B voltaria da tela, e o
 * teste de botao nunca chegaria ao B. Os controles do headset (nomeados
 * "Device 0x...") nao sao consumidos: sao o jeito de apertar "Back" na tela.
 *
 * **A vibracao passa pelo mesmo caminho do jogo** ([Rumble]), e nao por uma
 * chamada propria ao vibrador. Um teste que vibra por outra rota diria "o
 * motor funciona" mesmo quando a rota do jogo esta quebrada, que e justamente
 * o que se quer descobrir.
 */
class GamepadTestActivity: Activity()
{
	private val estado = PadState()
	private val prefs by lazy { StickPrefs(this) }
	private val ui = Handler(Looper.getMainLooper())

	private lateinit var mapa: Mapa
	private lateinit var leitura: TextView
	private lateinit var resumo: TextView
	private lateinit var ultimo: TextView
	private lateinit var dispositivos: TextView
	private lateinit var vibracao: TextView
	private lateinit var botaoForca: Button

	private var padId: Int? = null
	private var rumble: Rumble? = null
	private var hid: DualSenseHid? = null
	private var forca = FORCAS.size - 1
	private var passoRampa = 0

	private val parar = Runnable { rumble?.set(0, 0) }

	private val rampa = object: Runnable
	{
		override fun run()
		{
			val alvo = rumble ?: return
			passoRampa++
			if(passoRampa > PASSOS_RAMPA)
			{
				alvo.set(0, 0)
				return
			}
			val v = (255f * FORCAS[forca] * passoRampa / PASSOS_RAMPA).toInt()
			alvo.set(v, v)
			ui.postDelayed(this, 100)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			setBackgroundColor(Color.parseColor("#101014"))
			setPadding(64, 48, 64, 48)
		}

		root.addView(TextView(this).apply {
			text = Tx.tr("Gamepad test")
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
			gravity = Gravity.CENTER
			setTypeface(typeface, Typeface.BOLD)
		})

		root.addView(hint("Press buttons and move the sticks on the controller. Each "
				+ "control lights up while pressed and turns green once it has worked. "
				+ "The ring on each stick is the deadzone the app cuts."))

		dispositivos = texto(13f, "#9aa0a6")
		root.addView(dispositivos)

		mapa = Mapa(this)
		root.addView(mapa, LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT))

		ultimo = texto(15f, "#e8eaed")
		root.addView(ultimo)

		leitura = texto(13f, "#9aa0a6").apply { typeface = Typeface.MONOSPACE }
		root.addView(leitura)

		resumo = texto(14f, "#6fcf97")
		root.addView(resumo)

		root.addView(bigButton("Reset test") {
			estado.zerar()
			atualizar()
		})

		// ------------------------------------------------------- vibracao
		root.addView(TextView(this).apply {
			text = Tx.tr("Vibration test")
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			gravity = Gravity.CENTER
			setPadding(0, 40, 0, 0)
			setTypeface(typeface, Typeface.BOLD)
		})
		root.addView(hint("Each button runs the motor for one second at the chosen "
				+ "strength. The left motor is the heavy one, the right one the light one."))

		vibracao = texto(13f, "#9aa0a6")
		root.addView(vibracao)

		botaoForca = bigButton("Strength: {0}") {
			forca = (forca + 1) % FORCAS.size
			rotuloForca()
		}
		root.addView(botaoForca)
		rotuloForca()

		val linhaMotores = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		linhaMotores.addView(smallButton("Left motor") { vibrar(1f, 0f) })
		linhaMotores.addView(smallButton("Right motor") { vibrar(0f, 1f) })
		linhaMotores.addView(smallButton("Both motors") { vibrar(1f, 1f) })
		linhaMotores.addView(smallButton("Ramp up") { subir() })
		root.addView(linhaMotores)

		root.addView(bigButton("DualSense haptics bench") { ensaiarHaptica() })

		root.addView(hint("Felt nothing? Then the controller exposes no motor to Android, "
				+ "or its driver ignores the command. Diagnostics lists what each pad answered."))

		root.addView(bigButton("Back") { finish() })

		setContentView(ScrollView(this).apply {
			addView(root, LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT))
		})

		listarDispositivos()
		atualizar()
		Trace.log(this, "Gamepad test opened")
	}

	// ------------------------------------------------------------ entrada

	private fun ehPad(device: InputDevice?): Boolean
	{
		if(device == null)
			return false
		val fontes = device.sources
		val gamepad =
			fontes and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
			fontes and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
		return gamepad && !device.name.startsWith("Device 0x")
	}

	private fun anotar(device: InputDevice)
	{
		if(padId == device.id)
			return
		padId = device.id
		listarDispositivos()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		val device = event.device
		if(!ehPad(device))
			return super.dispatchKeyEvent(event)
		if(device != null)
			anotar(device)

		if(event.repeatCount == 0 &&
				(event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP))
		{
			val apertou = event.action == KeyEvent.ACTION_DOWN
			val conhecida = estado.tecla(event.keyCode, apertou)
			if(apertou)
			{
				val nome = if(conhecida) Tx.tr(PadState.nome(event.keyCode))
						else KeyEvent.keyCodeToString(event.keyCode)
				ultimo.text = Tx.f("last input: {0}", nome)
			}
			atualizar()
		}
		// Consome tambem o que nao conhece: um botao fora do mapa continua
		// aparecendo em "ultima entrada", e nao dispara nada na tela.
		return true
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		val joystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
		val device = event.device
		if(!joystick || event.action != MotionEvent.ACTION_MOVE || !ehPad(device))
			return super.onGenericMotionEvent(event)
		if(device != null)
			anotar(device)

		// Gatilho pode chegar por um de dois eixos, conforme o driver: vale o
		// maior, e o outro fica em zero.
		val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
				event.getAxisValue(MotionEvent.AXIS_BRAKE))
		val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
				event.getAxisValue(MotionEvent.AXIS_GAS))
		estado.eixos(
			event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
			event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ),
			l2, r2)
		estado.hat(event.getAxisValue(MotionEvent.AXIS_HAT_X),
				event.getAxisValue(MotionEvent.AXIS_HAT_Y))
		atualizar()
		return true
	}

	/**
	 * O painel de toque do DualSense chega como um mouse: o clique e um
	 * ACTION_DOWN de fonte de mouse vinda do proprio gamepad. O toque comum do
	 * ponteiro do headset nao passa por aqui, porque o filtro exige que o
	 * dispositivo seja um gamepad que nao e do headset.
	 */
	override fun dispatchTouchEvent(ev: MotionEvent): Boolean
	{
		val mouse = ev.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
		if(mouse && ehPad(ev.device))
		{
			when(ev.actionMasked)
			{
				MotionEvent.ACTION_DOWN -> estado.painel(true)
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> estado.painel(false)
			}
			atualizar()
		}
		return super.dispatchTouchEvent(ev)
	}

	// -------------------------------------------------------------- tela

	private fun atualizar()
	{
		leitura.text = "L  x ${PadState.fmt(estado.lx)}  y ${PadState.fmt(estado.ly)}" +
				"  max ${PadState.pct(estado.maxEsq)}\n" +
				"R  x ${PadState.fmt(estado.rx)}  y ${PadState.fmt(estado.ry)}" +
				"  max ${PadState.pct(estado.maxDir)}\n" +
				"L2 ${"%.2f".format(java.util.Locale.US, estado.l2)}  max " +
				"${"%.2f".format(java.util.Locale.US, estado.maxL2)}   " +
				"R2 ${"%.2f".format(java.util.Locale.US, estado.r2)}  max " +
				"${"%.2f".format(java.util.Locale.US, estado.maxR2)}"
		resumo.text = Tx.f("Tested: buttons {0}/{1}, sticks {2}/2, triggers {3}/2",
				estado.botoesTestados(), PadState.TODOS.size,
				estado.analogicosTestados(), estado.gatilhosTestados())
		if(ultimo.text.isEmpty())
			ultimo.text = Tx.f("last input: {0}", Tx.tr("none yet"))
		mapa.invalidate()
	}

	private fun listarDispositivos()
	{
		val linhas = StringBuilder()
		for(id in InputDevice.getDeviceIds())
		{
			val device = InputDevice.getDevice(id) ?: continue
			val fontes = device.sources
			val gamepad =
				fontes and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
				fontes and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
			if(!gamepad)
				continue
			if(linhas.isEmpty())
				linhas.append(Tx.tr("Connected gamepads")).append(":\n")
			linhas.append("• ").append(device.name).append(" (id ").append(id).append(")")
			if(device.name.startsWith("Device 0x"))
				linhas.append(" — ").append(Tx.tr("headset controller"))
			if(id == padId)
				linhas.append(" — ").append(Tx.tr("in use"))
			linhas.append("\n")
		}
		dispositivos.text = if(linhas.isEmpty())
			Tx.tr("No gamepad detected. Press a button on the controller.")
		else linhas.toString().trimEnd()
		vibracao.text = Tx.f("Motors Android sees: {0}", motores())
	}

	/** O que o Android diz que o pad tem, pela mesma pergunta que o [Rumble] faz. */
	private fun motores(): String
	{
		val id = padId ?: Rumble.gamepadDeviceId() ?: return Tx.tr("no gamepad")
		val device = InputDevice.getDevice(id) ?: return Tx.tr("no gamepad")
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			val manager = device.vibratorManager
			val ids = manager.vibratorIds
			if(ids.isNotEmpty())
			{
				val variavel = ids.all { manager.getVibrator(it).hasAmplitudeControl() }
				return Tx.f("{0} motor(s), {1}", ids.size,
						Tx.tr(if(variavel) "variable strength" else "fixed strength"))
			}
		}
		@Suppress("DEPRECATION")
		val unico = device.vibrator
		if(unico != null && unico.hasVibrator())
			return Tx.f("{0} motor(s), {1}", 1,
					Tx.tr(if(unico.hasAmplitudeControl()) "variable strength" else "fixed strength"))
		return Tx.tr("none — this controller exposes no motor to Android")
	}

	// ----------------------------------------------------------- vibracao

	/** Cria o [Rumble] na primeira vez; o `attach` dele toca uma buzina de teste. */
	private fun obterRumble(): Pair<Rumble, Boolean>
	{
		rumble?.let { return Pair(it, false) }
		val novo = Rumble(true)
		novo.attach(padId ?: Rumble.gamepadDeviceId())
		rumble = novo
		return Pair(novo, true)
	}

	private fun vibrar(esq: Float, dir: Float)
	{
		val (alvo, primeira) = obterRumble()
		ui.removeCallbacks(rampa)
		ui.removeCallbacks(parar)
		// Na primeira vez o attach ja tocou a buzina de teste (200 ms); o
		// comando espera ela acabar em vez de se sobrepor a ela.
		val espera = if(primeira) BUZINA_ESPERA_MS else 0L
		ui.postDelayed({
			alvo.set((255f * esq * FORCAS[forca]).toInt(), (255f * dir * FORCAS[forca]).toInt())
		}, espera)
		ui.postDelayed(parar, espera + DURACAO_MS)
		Trace.log(this, "Gamepad test: rumble left=$esq right=$dir strength=${FORCAS[forca]}")
	}

	private fun subir()
	{
		val (_, primeira) = obterRumble()
		ui.removeCallbacks(rampa)
		ui.removeCallbacks(parar)
		passoRampa = 0
		ui.postDelayed(rampa, if(primeira) BUZINA_ESPERA_MS else 0L)
		Trace.log(this, "Gamepad test: rumble ramp")
	}

	private fun rotuloForca()
	{
		botaoForca.text = Tx.f("Strength: {0}", "${(FORCAS[forca] * 100).toInt()}%")
	}

	/**
	 * A bancada de haptica do DualSense, a mesma da tela de diagnostico. Fica
	 * aqui tambem porque e onde quem testa o controle vai procurar vibracao.
	 */
	private fun ensaiarHaptica()
	{
		if(!DualSenseHid.disponivel(this))
		{
			vibracao.text = Tx.tr("Asking for Bluetooth permission. Answer the prompt, then press the button again.")
			requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), PEDIDO_BLUETOOTH)
			return
		}
		vibracao.text = Tx.tr("Playing three tones on the DualSense coil. Hold the controller.")
		val ponte = hid ?: DualSenseHid(this).also { hid = it }
		Thread {
			val relatorio = DualSenseHaptics(this, ponte).ensaiar()
			runOnUiThread { vibracao.text = relatorio }
		}.start()
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
		grantResults: IntArray)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if(requestCode == PEDIDO_BLUETOOTH && DualSenseHid.disponivel(this))
			vibracao.text = Tx.tr("Bluetooth permission granted. Press the button again.")
	}

	override fun onResume()
	{
		super.onResume()
		listarDispositivos()
	}

	override fun onPause()
	{
		super.onPause()
		// Nada de motor girando com a tela fora de vista.
		ui.removeCallbacks(rampa)
		ui.removeCallbacks(parar)
		rumble?.stop()
		rumble = null
	}

	override fun onDestroy()
	{
		super.onDestroy()
		hid?.fechar()
	}

	// ------------------------------------------------------------ desenho

	/**
	 * O pad desenhado num espaco fixo de 1000 x 600 e escalado para a largura
	 * da tela. Cada forma tem tres estados: apagada, verde (ja funcionou) e
	 * ambar (apertada agora).
	 */
	private inner class Mapa(context: Context): View(context)
	{
		private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
		private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			textAlign = Paint.Align.CENTER
			textSize = 16f
		}
		private val area = RectF()

		override fun onMeasure(larguraSpec: Int, alturaSpec: Int)
		{
			val largura = MeasureSpec.getSize(larguraSpec).let { if(it > 0) it else 1000 }
			setMeasuredDimension(largura, (largura * ALTURA / LARGURA).toInt())
		}

		override fun onDraw(canvas: Canvas)
		{
			canvas.save()
			val escala = width / LARGURA
			canvas.scale(escala, escala)

			// Gatilhos e ombros.
			gatilho(canvas, 90f, 20f, "L2", estado.l2, estado.maxL2, PadState.L2)
			gatilho(canvas, 710f, 20f, "R2", estado.r2, estado.maxR2, PadState.R2)
			pilula(canvas, 90f, 62f, 290f, 94f, PadState.L1, "L1")
			pilula(canvas, 710f, 62f, 910f, 94f, PadState.R1, "R1")

			// Painel e botoes do centro.
			pilula(canvas, 360f, 70f, 640f, 130f, PadState.PAINEL, Tx.tr("Touchpad"))
			pilula(canvas, 330f, 160f, 440f, 194f, PadState.CRIAR, Tx.tr("Create"))
			pilula(canvas, 560f, 160f, 670f, 194f, PadState.OPCOES, Tx.tr("Options"))
			redondo(canvas, 500f, 235f, 30f, PadState.PS, "PS")

			// D-pad.
			pilula(canvas, 148f, 162f, 192f, 206f, PadState.CIMA, "▲")
			pilula(canvas, 148f, 254f, 192f, 298f, PadState.BAIXO, "▼")
			pilula(canvas, 102f, 208f, 146f, 252f, PadState.ESQUERDA, "◀")
			pilula(canvas, 194f, 208f, 238f, 252f, PadState.DIREITA, "▶")

			// Botoes de acao.
			redondo(canvas, 830f, 170f, 32f, PadState.TRIANGULO, Tx.tr("Triangle"))
			redondo(canvas, 830f, 290f, 32f, PadState.CRUZ, Tx.tr("Cross"))
			redondo(canvas, 770f, 230f, 32f, PadState.QUADRADO, Tx.tr("Square"))
			redondo(canvas, 890f, 230f, 32f, PadState.BOLA, Tx.tr("Circle"))

			// Analogicos.
			analogico(canvas, 340f, 460f, 110f, estado.lx, estado.ly, estado.maxEsq,
					prefs.left, PadState.L3, "L3")
			analogico(canvas, 660f, 460f, 110f, estado.rx, estado.ry, estado.maxDir,
					prefs.right, PadState.R3, "R3")

			canvas.restore()
		}

		private fun preencher(codigo: Int)
		{
			tinta.style = Paint.Style.FILL
			tinta.color = when
			{
				estado.apertado(codigo) -> AMBAR
				estado.testado(codigo) -> VERDE
				else -> CINZA
			}
		}

		private fun rotular(canvas: Canvas, texto: String, x: Float, y: Float, codigo: Int)
		{
			letra.color = if(estado.apertado(codigo)) Color.BLACK else Color.WHITE
			canvas.drawText(texto, x, y + 6f, letra)
		}

		private fun redondo(canvas: Canvas, cx: Float, cy: Float, r: Float, codigo: Int, texto: String)
		{
			preencher(codigo)
			canvas.drawCircle(cx, cy, r, tinta)
			borda()
			canvas.drawCircle(cx, cy, r, tinta)
			rotular(canvas, texto, cx, cy, codigo)
		}

		private fun pilula(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float,
				codigo: Int, texto: String)
		{
			area.set(x0, y0, x1, y1)
			preencher(codigo)
			canvas.drawRoundRect(area, 12f, 12f, tinta)
			borda()
			canvas.drawRoundRect(area, 12f, 12f, tinta)
			rotular(canvas, texto, (x0 + x1) / 2f, (y0 + y1) / 2f, codigo)
		}

		private fun borda()
		{
			tinta.style = Paint.Style.STROKE
			tinta.strokeWidth = 2f
			tinta.color = BORDA
		}

		/** Barra de gatilho analogico: enche com o valor; a tecla do gatilho acende a borda. */
		private fun gatilho(canvas: Canvas, x: Float, y: Float, nome: String,
				valor: Float, maximo: Float, codigo: Int)
		{
			area.set(x, y, x + 200f, y + 32f)
			tinta.style = Paint.Style.FILL
			tinta.color = CINZA
			canvas.drawRoundRect(area, 10f, 10f, tinta)
			area.set(x, y, x + 200f * valor.coerceIn(0f, 1f), y + 32f)
			tinta.color = AMBAR
			canvas.drawRoundRect(area, 10f, 10f, tinta)

			area.set(x, y, x + 200f, y + 32f)
			tinta.style = Paint.Style.STROKE
			tinta.strokeWidth = if(estado.apertado(codigo)) 5f else 2f
			tinta.color = when
			{
				estado.apertado(codigo) -> AMBAR
				maximo >= PadState.GATILHO_CHEIO -> VERDE_BORDA
				else -> BORDA
			}
			canvas.drawRoundRect(area, 10f, 10f, tinta)

			letra.color = Color.WHITE
			canvas.drawText(nome + "  " + "%.2f".format(java.util.Locale.US, valor),
					x + 100f, y + 22f, letra)
		}

		private fun analogico(canvas: Canvas, cx: Float, cy: Float, raio: Float,
				x: Float, y: Float, maximo: Float, cal: StickPrefs.Calibragem,
				codigo: Int, nome: String)
		{
			tinta.style = Paint.Style.FILL
			tinta.color = CINZA
			canvas.drawCircle(cx, cy, raio, tinta)

			tinta.style = Paint.Style.STROKE
			tinta.strokeWidth = if(estado.apertado(codigo)) 7f else 3f
			tinta.color = when
			{
				estado.apertado(codigo) -> AMBAR
				maximo >= PadState.CURSO_CHEIO -> VERDE_BORDA
				else -> BORDA
			}
			canvas.drawCircle(cx, cy, raio, tinta)

			// Cruz do centro geometrico.
			tinta.strokeWidth = 2f
			tinta.color = BORDA
			canvas.drawLine(cx - 14f, cy, cx + 14f, cy, tinta)
			canvas.drawLine(cx, cy - 14f, cx, cy + 14f, tinta)

			// Zona morta gravada, em volta do repouso medido.
			tinta.strokeWidth = 3f
			tinta.color = ANEL
			canvas.drawCircle(cx + cal.centerX * raio, cy + cal.centerY * raio,
					cal.deadzone * raio, tinta)

			letra.color = Color.parseColor("#9aa0a6")
			canvas.drawText(nome, cx, cy + raio - 12f, letra)

			tinta.style = Paint.Style.FILL
			tinta.color = Color.WHITE
			canvas.drawCircle(cx + x * raio, cy + y * raio, 10f, tinta)
		}
	}

	// ------------------------------------------------------------ pedacos

	private fun texto(tamanho: Float, cor: String) = TextView(this).apply {
		setTextColor(Color.parseColor(cor))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanho)
		gravity = Gravity.CENTER
		setPadding(0, 8, 0, 8)
	}

	private fun hint(texto: String) = TextView(this).apply {
		text = Tx.tr(texto)
		setTextColor(Color.parseColor("#9aa0a6"))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
		gravity = Gravity.CENTER
		setPadding(0, 8, 0, 16)
	}

	private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
		text = Tx.tr(label)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
		setOnClickListener { onClick() }
		layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT
		).apply { setMargins(0, 12, 0, 12) }
	}

	private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
		text = Tx.tr(label)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		setOnClickListener { onClick() }
		layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
			.apply { weight = 1f }
	}

	private companion object
	{
		const val LARGURA = 1000f
		const val ALTURA = 600f

		val CINZA = Color.parseColor("#1a1a22")
		val BORDA = Color.parseColor("#3a3a46")
		val VERDE = Color.parseColor("#1f4a30")
		val VERDE_BORDA = Color.parseColor("#4caf7a")
		val AMBAR = Color.parseColor("#e5a33d")
		val ANEL = Color.parseColor("#5c8ce0")

		/** Forcas do teste, em fracao do maximo. */
		val FORCAS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)

		const val DURACAO_MS = 1000L
		const val BUZINA_ESPERA_MS = 400L
		const val PASSOS_RAMPA = 30
		const val PEDIDO_BLUETOOTH = 4301
	}
}
