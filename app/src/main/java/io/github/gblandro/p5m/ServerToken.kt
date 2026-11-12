// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import java.security.SecureRandom

/**
 * Segredo curto no caminho da URL dos servidores locais ([LogServer] e
 * [PsnLoginRelay]).
 *
 * Os dois falam HTTP simples e ouviam qualquer um da rede local. O segredo não
 * protege contra quem escuta o Wi-Fi — o tráfego continua sem cifra —, mas
 * fecha o caso comum: outro aparelho da mesma rede varrendo a porta 8787 ou
 * 8788 e lendo o diário ou mandando um código de login. Quem não tem a URL que
 * a tela mostra recebe 404.
 *
 * Seis caracteres de um alfabeto sem letras ambíguas (sem 0/o, 1/l/i): dá para
 * digitar no celular sem erro, e como o servidor só vive enquanto a tela está
 * aberta, adivinhar por força bruta não é realista.
 */
internal object ServerToken
{
	private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
	private val random = SecureRandom()

	fun generate(length: Int = 6): String =
		buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

	/**
	 * Tira o segredo do início do caminho. Devolve o resto (sempre começando
	 * com "/") ou null se o caminho não traz o segredo certo.
	 */
	fun strip(path: String, token: String): String?
	{
		val prefix = "/$token"
		if(!path.startsWith(prefix))
			return null
		val rest = path.substring(prefix.length)
		return when
		{
			rest.isEmpty() -> "/"
			rest[0] == '/' -> rest
			rest[0] == '?' -> "/$rest"
			else -> null
		}
	}
}
