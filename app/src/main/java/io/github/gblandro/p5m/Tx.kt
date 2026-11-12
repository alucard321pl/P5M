// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import java.util.Locale

/**
 * Tradução dos textos da interface.
 *
 * Os textos do app nascem em inglês, no próprio código. Em vez de espalhar
 * `strings.xml` por telas que também usam o texto como chave de lógica, a
 * tradução acontece só na hora de **mostrar**: a chave continua sendo o inglês
 * e [tr] devolve a versão polonesa se o sistema estiver em polonês. Um texto
 * sem tradução aparece em inglês, nunca vazio nem quebrado.
 *
 * O dicionário fica em `TxPl.kt`. Para dar a mesma palavra duas formas
 * (concordância de gênero, por exemplo) a chave pode ter um contexto:
 * `tr("strong", "sharp")` procura "sharp:strong" antes de "strong".
 */
object Tx
{
	private fun polish() = Locale.getDefault().language == "pl"

	fun tr(en: String, context: String? = null): String
	{
		if(!polish())
			return en
		if(context != null)
			PL_STRINGS["$context:$en"]?.let { return it }
		return PL_STRINGS[en] ?: en
	}

	/** Valor "desligado" em qualquer idioma: a lista o mostra apagado. */
	fun isOff(value: String) = value == "off" || value == "off (2D)"
			|| value == "wył." || value == "wyłączona" || value == "wyłączony"
}
